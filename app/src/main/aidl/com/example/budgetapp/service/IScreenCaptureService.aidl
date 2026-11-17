package com.example.budgetapp.service;

import android.os.Bundle;
import com.example.budgetapp.service.IScreenCaptureListener;

/** Fixed read-only operations; intentionally no arbitrary shell, input, or screenshot API. */
interface IScreenCaptureService {
    int initialize(IScreenCaptureListener listener) = 4;
    void releaseAutomation() = 3;
    Bundle sample(boolean allowHierarchy, in String[] rulePackages) = 7;
    boolean startDiagnostic(long id, String packageName, long deadline) = 8;
    @nullable Bundle readDiagnostic(long id, int seenFrame, boolean stop) = 9;
    @nullable String readDiagnosticTrace(long id, int offset) = 10;
    void destroy() = 16777114;
}
