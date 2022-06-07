package com.example.facedetection;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

public interface SimilarityClassifier {
    class Recognition { // An immutable result returned by a Classifier describing what was recognized

        private final String id, title; // Specific to the class, not the instance of the object
        private final Float distance;
        Object extra;

        public Recognition(final String id, final String title, final Float distance) {
            this.id = id;
            this.title = title;
            this.distance = distance;
            this.extra = null;
        }

        public void setExtra(Object extra) { this.extra = extra; }
        public Object getExtra() { return this.extra; }

        @SuppressLint("DefaultLocale")
        @NonNull
        @Override
        public String toString() {
            String resultString = "";
            if (id != null)resultString += "id: [" + id + "] ";
            if (title != null) resultString += title + " ";
            if (distance != null) resultString += String.format("(%.1f%%) ", distance * 100.0f);
            return resultString.trim();
        }
    }
}
