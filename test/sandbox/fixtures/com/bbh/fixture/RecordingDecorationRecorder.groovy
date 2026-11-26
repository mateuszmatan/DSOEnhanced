package com.bbh.fixture

import com.bbh.remediation.port.DecorationRecorder
import devsecops.test.FakeFlowInterruptedException

class RecordingDecorationRecorder implements DecorationRecorder {

    List<Map> decorations = []
    String failure = ''
    boolean abort = false

    void recorded(Map decoration) {
        decorations << decoration
        if (abort) throw new FakeFlowInterruptedException('the build was aborted')
        if (failure) throw new Exception(failure)
    }
}
