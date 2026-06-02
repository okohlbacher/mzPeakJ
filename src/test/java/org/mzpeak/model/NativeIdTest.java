package org.mzpeak.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NativeIdTest {

    @Test
    void parsesCommonVendorFormats() {
        assertThat(NativeId.scanNumber("controllerType=0 controllerNumber=1 scan=3")).hasValue(3); // Thermo
        assertThat(NativeId.scanNumber("function=1 process=0 scan=42")).hasValue(42);              // Waters
        assertThat(NativeId.scanNumber("merged=212 frame=7 scan=1")).hasValue(1);                  // scan beats frame
        assertThat(NativeId.scanNumber("frame=7")).hasValue(7);                                     // frame fallback (Bruker)
        assertThat(NativeId.scanNumber("sample=1 period=1 cycle=123 experiment=2")).hasValue(123); // SCIEX cycle
        assertThat(NativeId.scanNumber("scanId=99")).hasValue(99);                                 // Agilent
        assertThat(NativeId.scanNumber("index=5")).hasValue(5);                                    // generic
    }

    @Test
    void emptyWhenNoScanLikeToken() {
        assertThat(NativeId.scanNumber("controllerType=0 controllerNumber=1")).isEmpty();
        assertThat(NativeId.scanNumber(null)).isEmpty();
    }
}
