package com.example.budgetapp.service;

/** A coalesced sampling deadline in SystemClock.uptimeMillis(), with no screen data. */
oneway interface IScreenCaptureListener {
    void onScreenChanged(long sampleAt);
}
