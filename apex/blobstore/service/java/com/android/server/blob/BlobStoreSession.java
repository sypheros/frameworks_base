/*
 * Copyright 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.blob;

import static android.app.blob.BlobStoreManager.COMMIT_RESULT_ERROR;
import static android.app.blob.XmlTags.ATTR_ID;
import static android.app.blob.XmlTags.ATTR_PACKAGE;
import static android.app.blob.XmlTags.ATTR_UID;
import static android.app.blob.XmlTags.TAG_ACCESS_MODE;
import static android.app.blob.XmlTags.TAG_BLOB_HANDLE;
import static android.os.Trace.TRACE_TAG_SYSTEM_SERVER;
import static android.system.OsConstants.O_CREAT;
import static android.system.OsConstants.O_RDONLY;
import static android.system.OsConstants.O_RDWR;
import static android.system.OsConstants.SEEK_SET;

import static com.android.server.blob.BlobStoreConfig.LOGV;
import static com.android.server.blob.BlobStoreConfig.TAG;

import android.annotation.BytesLong;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.blob.BlobHandle;
import android.app.blob.IBlobCommitCallback;
import android.app.blob.IBlobStoreSession;
import android.content.Context;
import android.os.Binder;
import android.os.FileUtils;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.RevocableFileDescriptor;
import android.os.Trace;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.Os;
import android.util.ExceptionUtils;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;
import com.android.internal.util.XmlUtils;
import com.android.server.blob.BlobStoreManagerService.DumpArgs;
import com.android.server.blob.BlobStoreManagerService.SessionStateChangeListener;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** TODO: add doc */
@VisibleForTesting
class BlobStoreSession extends IBlobStoreSession.Stub {

    static final int STATE_OPENED = 1;
    static final int STATE_CLOSED = 0;
    static final int STATE_ABANDONED = 2;
    static final int STATE_COMMITTED = 3;
    static final int STATE_VERIFIED_VALID = 4;
    static final int STATE_VERIFIED_INVALID = 5;

    private final Object mSessionLock = new Object();

    private final Context mContext;
    private final SessionStateChangeListener mListener;

    private final BlobHandle mBlobHandle;
    private final long mSessionId;
    private final int mOwnerUid;
    private final String mOwnerPackageName;

    // Do not access this directly, instead use getSessionFile().
    private File mSessionFile;

    @GuardedBy("mRevocableFds")
    private ArrayList<RevocableFileDescriptor> mRevocableFds = new ArrayList<>();

    @GuardedBy("mSessionLock")
    private int mState = STATE_CLOSED;

    @GuardedBy("mSessionLock")
    private final BlobAccessMode mBlobAccessMode = new BlobAccessMode();

    @GuardedBy("mSessionLock")
    private IBlobCommitCallback mBlobCommitCallback;

    BlobStoreSession(Context context, long sessionId, BlobHandle blobHandle,
            int ownerUid, String ownerPackageName, SessionStateChangeListener listener) {
        this.mContext = context;
        this.mBlobHandle = blobHandle;
        this.mSessionId = sessionId;
        this.mOwnerUid = ownerUid;
        this.mOwnerPackageName = ownerPackageName;
        this.mListener = listener;
    }

    public BlobHandle getBlobHandle() {
        return mBlobHandle;
    }

    public long getSessionId() {
        return mSessionId;
    }

    public int getOwnerUid() {
        return mOwnerUid;
    }

    public String getOwnerPackageName() {
        return mOwnerPackageName;
    }

    boolean hasAccess(int callingUid, String callingPackageName) {
        return mOwnerUid == callingUid && mOwnerPackageName.equals(callingPackageName);
    }

    void open() {
        synchronized (mSessionLock) {
            if (isFinalized()) {
                throw new IllegalStateException("Not allowed to open session with state: "
                        + stateToString(mState));
            }
            mState = STATE_OPENED;
        }
    }

    int getState() {
        synchronized (mSessionLock) {
            return mState;
        }
    }

    void sendCommitCallbackResult(int result) {
        synchronized (mSessionLock) {
            try {
                mBlobCommitCallback.onResult(result);
            } catch (RemoteException e) {
                Slog.d(TAG, "Error sending the callback result", e);
            }
            mBlobCommitCallback = null;
        }
    }

    BlobAccessMode getBlobAccessMode() {
        synchronized (mSessionLock) {
            return mBlobAccessMode;
        }
    }

    boolean isFinalized() {
        synchronized (mSessionLock) {
            return mState == STATE_COMMITTED || mState == STATE_ABANDONED;
        }
    }

    @Override
    @NonNull
    public ParcelFileDescriptor openWrite(@BytesLong long offsetBytes,
            @BytesLong long lengthBytes) {
        Preconditions.checkArgumentNonnegative(offsetBytes, "offsetBytes must not be negative");

        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to write in state: "
                        + stateToString(mState));
            }

            try {
                return openWriteLocked(offsetBytes, lengthBytes);
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @GuardedBy("mSessionLock")
    @NonNull
    private ParcelFileDescriptor openWriteLocked(@BytesLong long offsetBytes,
            @BytesLong long lengthBytes) throws IOException {
        // TODO: Add limit on active open sessions/writes/reads
        FileDescriptor fd = null;
        try {
            final File sessionFile = getSessionFile();
            if (sessionFile == null) {
                throw new IllegalStateException("Couldn't get the file for this session");
            }
            fd = Os.open(sessionFile.getPath(), O_CREAT | O_RDWR, 0600);
            if (offsetBytes > 0) {
                final long curOffset = Os.lseek(fd, offsetBytes, SEEK_SET);
                if (curOffset != offsetBytes) {
                    throw new IllegalStateException("Failed to seek " + offsetBytes
                            + "; curOffset=" + offsetBytes);
                }
            }
            if (lengthBytes > 0) {
                mContext.getSystemService(StorageManager.class).allocateBytes(fd, lengthBytes);
            }
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
        return createRevocableFdLocked(fd);
    }

    @Override
    @NonNull
    public ParcelFileDescriptor openRead() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to read in state: "
                        + stateToString(mState));
            }

            try {
                return openReadLocked();
            } catch (IOException e) {
                throw ExceptionUtils.wrap(e);
            }
        }
    }

    @GuardedBy("mSessionLock")
    @NonNull
    private ParcelFileDescriptor openReadLocked() throws IOException {
        FileDescriptor fd = null;
        try {
            final File sessionFile = getSessionFile();
            if (sessionFile == null) {
                throw new IllegalStateException("Couldn't get the file for this session");
            }
            fd = Os.open(sessionFile.getPath(), O_RDONLY, 0);
        } catch (ErrnoException e) {
            e.rethrowAsIOException();
        }
        return createRevocableFdLocked(fd);
    }

    @Override
    @BytesLong
    public long getSize() {
        return getSessionFile().length();
    }

    @Override
    public void allowPackageAccess(@NonNull String packageName,
            @NonNull byte[] certificate) {
        assertCallerIsOwner();
        Objects.requireNonNull(packageName, "packageName must not be null");
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowPackageAccess(packageName, certificate);
        }
    }

    @Override
    public void allowSameSignatureAccess() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowSameSignatureAccess();
        }
    }

    @Override
    public void allowPublicAccess() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to change access type in state: "
                        + stateToString(mState));
            }
            mBlobAccessMode.allowPublicAccess();
        }
    }

    @Override
    public boolean isPackageAccessAllowed(@NonNull String packageName,
            @NonNull byte[] certificate) {
        assertCallerIsOwner();
        Objects.requireNonNull(packageName, "packageName must not be null");
        Preconditions.checkByteArrayNotEmpty(certificate, "certificate");

        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isPackageAccessAllowed(packageName, certificate);
        }
    }

    @Override
    public boolean isSameSignatureAccessAllowed() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isSameSignatureAccessAllowed();
        }
    }

    @Override
    public boolean isPublicAccessAllowed() {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                throw new IllegalStateException("Not allowed to get access type in state: "
                        + stateToString(mState));
            }
            return mBlobAccessMode.isPublicAccessAllowed();
        }
    }

    @Override
    public void close() {
        closeSession(STATE_CLOSED);
    }

    @Override
    public void abandon() {
        closeSession(STATE_ABANDONED);
    }

    @Override
    public void commit(IBlobCommitCallback callback) {
        synchronized (mSessionLock) {
            mBlobCommitCallback = callback;

            closeSession(STATE_COMMITTED);
        }
    }

    private void closeSession(int state) {
        assertCallerIsOwner();
        synchronized (mSessionLock) {
            if (mState != STATE_OPENED) {
                if (state == STATE_CLOSED) {
                    // Just trying to close the session which is already deleted or abandoned,
                    // ignore.
                    return;
                } else {
                    throw new IllegalStateException("Not allowed to delete or abandon a session"
                            + " with state: " + stateToString(mState));
                }
            }

            mState = state;
            revokeAllFdsLocked();

            mListener.onStateChanged(this);
        }
    }

    void verifyBlobData() {
        byte[] actualDigest = null;
        try {
            Trace.traceBegin(TRACE_TAG_SYSTEM_SERVER,
                    "computeBlobDigest-i" + mSessionId + "-l" + getSessionFile().length());
            actualDigest = FileUtils.digest(getSessionFile(), mBlobHandle.algorithm);
        } catch (IOException | NoSuchAlgorithmException e) {
            Slog.e(TAG, "Error computing the digest", e);
        } finally {
            Trace.traceEnd(TRACE_TAG_SYSTEM_SERVER);
        }
        synchronized (mSessionLock) {
            if (actualDigest != null && Arrays.equals(actualDigest, mBlobHandle.digest)) {
                mState = STATE_VERIFIED_VALID;
                // Commit callback will be sent once the data is persisted.
            } else {
                if (LOGV) {
                    Slog.v(TAG, "Digest of the data didn't match the given BlobHandle.digest");
                }
                mState = STATE_VERIFIED_INVALID;
                sendCommitCallbackResult(COMMIT_RESULT_ERROR);
            }
            mListener.onStateChanged(this);
        }
    }

    @GuardedBy("mSessionLock")
    private void revokeAllFdsLocked() {
        for (int i = mRevocableFds.size() - 1; i >= 0; --i) {
            mRevocableFds.get(i).revoke();
        }
        mRevocableFds.clear();
    }

    @GuardedBy("mSessionLock")
    @NonNull
    private ParcelFileDescriptor createRevocableFdLocked(FileDescriptor fd)
            throws IOException {
        final RevocableFileDescriptor revocableFd =
                new RevocableFileDescriptor(mContext, fd);
        synchronized (mRevocableFds) {
            mRevocableFds.add(revocableFd);
        }
        revocableFd.addOnCloseListener((e) -> {
            synchronized (mRevocableFds) {
                mRevocableFds.remove(revocableFd);
            }
        });
        return revocableFd.getRevocableFileDescriptor();
    }

    @Nullable
    File getSessionFile() {
        if (mSessionFile == null) {
            mSessionFile = BlobStoreConfig.prepareBlobFile(mSessionId);
        }
        return mSessionFile;
    }

    @NonNull
    static String stateToString(int state) {
        switch (state) {
            case STATE_OPENED:
                return "<opened>";
            case STATE_CLOSED:
                return "<closed>";
            case STATE_ABANDONED:
                return "<abandoned>";
            case STATE_COMMITTED:
                return "<committed>";
            default:
                Slog.wtf(TAG, "Unknown state: " + state);
                return "<unknown>";
        }
    }

    @Override
    public String toString() {
        return "BlobStoreSession {"
                + "id:" + mSessionId
                + ",handle:" + mBlobHandle
                + ",uid:" + mOwnerUid
                + ",pkg:" + mOwnerPackageName
                + "}";
    }

    private void assertCallerIsOwner() {
        final int callingUid = Binder.getCallingUid();
        if (callingUid != mOwnerUid) {
            throw new SecurityException(mOwnerUid + " is not the session owner");
        }
    }

    void dump(IndentingPrintWriter fout, DumpArgs dumpArgs) {
        synchronized (mSessionLock) {
            fout.println("state: " + stateToString(mState));
            fout.println("ownerUid: " + mOwnerUid);
            fout.println("ownerPkg: " + mOwnerPackageName);

            fout.println("blobHandle:");
            fout.increaseIndent();
            mBlobHandle.dump(fout, dumpArgs.shouldDumpFull());
            fout.decreaseIndent();

            fout.println("accessMode:");
            fout.increaseIndent();
            mBlobAccessMode.dump(fout);
            fout.decreaseIndent();

            fout.println("Open fds: #" + mRevocableFds.size());
        }
    }

    void writeToXml(@NonNull XmlSerializer out) throws IOException {
        synchronized (mSessionLock) {
            XmlUtils.writeLongAttribute(out, ATTR_ID, mSessionId);
            XmlUtils.writeStringAttribute(out, ATTR_PACKAGE, mOwnerPackageName);
            XmlUtils.writeIntAttribute(out, ATTR_UID, mOwnerUid);

            out.startTag(null, TAG_BLOB_HANDLE);
            mBlobHandle.writeToXml(out);
            out.endTag(null, TAG_BLOB_HANDLE);

            out.startTag(null, TAG_ACCESS_MODE);
            mBlobAccessMode.writeToXml(out);
            out.endTag(null, TAG_ACCESS_MODE);
        }
    }

    @Nullable
    static BlobStoreSession createFromXml(@NonNull XmlPullParser in, int version,
            @NonNull Context context, @NonNull SessionStateChangeListener stateChangeListener)
            throws IOException, XmlPullParserException {
        final long sessionId = XmlUtils.readLongAttribute(in, ATTR_ID);
        final String ownerPackageName = XmlUtils.readStringAttribute(in, ATTR_PACKAGE);
        final int ownerUid = XmlUtils.readIntAttribute(in, ATTR_UID);

        final int depth = in.getDepth();
        BlobHandle blobHandle = null;
        BlobAccessMode blobAccessMode = null;
        while (XmlUtils.nextElementWithin(in, depth)) {
            if (TAG_BLOB_HANDLE.equals(in.getName())) {
                blobHandle = BlobHandle.createFromXml(in);
            } else if (TAG_ACCESS_MODE.equals(in.getName())) {
                blobAccessMode = BlobAccessMode.createFromXml(in);
            }
        }

        if (blobHandle == null) {
            Slog.wtf(TAG, "blobHandle should be available");
            return null;
        }
        if (blobAccessMode == null) {
            Slog.wtf(TAG, "blobAccessMode should be available");
            return null;
        }

        final BlobStoreSession blobStoreSession = new BlobStoreSession(context, sessionId,
                blobHandle, ownerUid, ownerPackageName, stateChangeListener);
        blobStoreSession.mBlobAccessMode.allow(blobAccessMode);
        return blobStoreSession;
    }
}
