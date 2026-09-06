package com.wiyuka.acceleratedrecoiling.api;

public interface ICustomData {

    int getNativeId();
    void setNativeId(int id);

    void extractionBoundingBox(double[] doubleArray, int offset, double inflate);

    void setDensity(float i);

    float getDensity();
}
