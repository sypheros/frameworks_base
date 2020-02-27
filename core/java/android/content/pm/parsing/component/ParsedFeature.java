/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.content.pm.parsing.component;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.ArraySet;

import com.android.internal.util.DataClass;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link android.R.styleable#AndroidManifestFeature &lt;feature&gt;} tag parsed from the
 * manifest.
 *
 * @hide
 */
@DataClass(genAidl = false)
public class ParsedFeature implements Parcelable {
    /** Maximum length of featureId */
    public static final int MAX_FEATURE_ID_LEN = 50;

    /** Maximum amount of features per package */
    private static final int MAX_NUM_FEATURES = 1000;

    /** Id of the feature */
    public final @NonNull String id;

    /** User visible label fo the feature */
    public final @StringRes int label;

    /** Ids of previously declared features this feature inherits from */
    public final @NonNull List<String> inheritFrom;

    /**
     * @return Is this set of features a valid combination for a single package?
     */
    public static boolean isCombinationValid(@Nullable List<ParsedFeature> features) {
        if (features == null) {
            return true;
        }

        ArraySet<String> featureIds = new ArraySet<>(features.size());
        ArraySet<String> inheritFromFeatureIds = new ArraySet<>();

        int numFeatures = features.size();
        if (numFeatures > MAX_NUM_FEATURES) {
            return false;
        }

        for (int featureNum = 0; featureNum < numFeatures; featureNum++) {
            boolean wasAdded = featureIds.add(features.get(featureNum).id);
            if (!wasAdded) {
                // feature id is not unique
                return false;
            }
        }

        for (int featureNum = 0; featureNum < numFeatures; featureNum++) {
            ParsedFeature feature = features.get(featureNum);

            int numInheritFrom = feature.inheritFrom.size();
            for (int inheritFromNum = 0; inheritFromNum < numInheritFrom; inheritFromNum++) {
                String inheritFrom = feature.inheritFrom.get(inheritFromNum);

                if (featureIds.contains(inheritFrom)) {
                    // Cannot inherit from a feature that is still defined
                    return false;
                }

                boolean wasAdded = inheritFromFeatureIds.add(inheritFrom);
                if (!wasAdded) {
                    // inheritFrom is not unique
                    return false;
                }
            }
        }

        return true;
    }



    // Code below generated by codegen v1.0.14.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/parsing/component/ParsedFeature.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @android.annotation.IntDef(prefix = "MAX_", value = {
        MAX_FEATURE_ID_LEN,
        MAX_NUM_FEATURES
    })
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Max {}

    @DataClass.Generated.Member
    public static String maxToString(@Max int value) {
        switch (value) {
            case MAX_FEATURE_ID_LEN:
                    return "MAX_FEATURE_ID_LEN";
            case MAX_NUM_FEATURES:
                    return "MAX_NUM_FEATURES";
            default: return Integer.toHexString(value);
        }
    }

    /**
     * Creates a new ParsedFeature.
     *
     * @param id
     *   Id of the feature
     * @param label
     *   User visible label fo the feature
     * @param inheritFrom
     *   Ids of previously declared features this feature inherits from
     */
    @DataClass.Generated.Member
    public ParsedFeature(
            @NonNull String id,
            @StringRes int label,
            @NonNull List<String> inheritFrom) {
        this.id = id;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, id);
        this.label = label;
        com.android.internal.util.AnnotationValidations.validate(
                StringRes.class, null, label);
        this.inheritFrom = inheritFrom;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, inheritFrom);

        // onConstructed(); // You can define this method to get a callback
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        dest.writeString(id);
        dest.writeInt(label);
        dest.writeStringList(inheritFrom);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    protected ParsedFeature(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        String _id = in.readString();
        int _label = in.readInt();
        List<String> _inheritFrom = new ArrayList<>();
        in.readStringList(_inheritFrom);

        this.id = _id;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, id);
        this.label = _label;
        com.android.internal.util.AnnotationValidations.validate(
                StringRes.class, null, label);
        this.inheritFrom = _inheritFrom;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, inheritFrom);

        // onConstructed(); // You can define this method to get a callback
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<ParsedFeature> CREATOR
            = new Parcelable.Creator<ParsedFeature>() {
        @Override
        public ParsedFeature[] newArray(int size) {
            return new ParsedFeature[size];
        }

        @Override
        public ParsedFeature createFromParcel(@NonNull Parcel in) {
            return new ParsedFeature(in);
        }
    };

    @DataClass.Generated(
            time = 1581379861853L,
            codegenVersion = "1.0.14",
            sourceFile = "frameworks/base/core/java/android/content/pm/parsing/component/ParsedFeature.java",
            inputSignatures = "public static final  int MAX_FEATURE_ID_LEN\nprivate static final  int MAX_NUM_FEATURES\npublic final @android.annotation.NonNull java.lang.String id\npublic final @android.annotation.StringRes int label\npublic final @android.annotation.NonNull java.util.List<java.lang.String> inheritFrom\npublic static  boolean isCombinationValid(java.util.List<android.content.pm.parsing.component.ParsedFeature>)\nclass ParsedFeature extends java.lang.Object implements [android.os.Parcelable]\n@com.android.internal.util.DataClass(genAidl=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
