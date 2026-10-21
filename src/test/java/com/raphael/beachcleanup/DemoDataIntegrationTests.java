package com.raphael.beachcleanup;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DemoDataIntegrationTests {
    @TempDir Path directory;
    private String url() {
        return "jdbc:h2:file:" + directory.resolve("demo").toString().replace('\\', '/')
            + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE;LOCK_TIMEOUT=15000";
    }
    @Test void optInPreservesDataAndSurvivesRestarts() throws Exception {
        DemoDataAssertions.lifecycle(url(), "sa", "", false);
    }
    @Test void batchIsAtomicAndConcurrentStartsDoNotDuplicate() throws Exception {
        DemoDataAssertions.atomicAndConcurrent(url(), "sa", "", false);
    }
}
