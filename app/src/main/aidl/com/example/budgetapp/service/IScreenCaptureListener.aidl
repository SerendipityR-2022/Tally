package com.example.budgetapp.service;

/** A coalesced wake-up signal only, with no package names or window contents. */
oneway interface IScreenCaptureListener {
    void onScreenChanged();
}
