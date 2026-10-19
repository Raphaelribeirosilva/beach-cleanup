package com.raphael.beachcleanup;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.hibernate.dialect.MariaDBDialect;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;
import org.junit.jupiter.api.Test;
import com.raphael.beachcleanup.config.MySqlVendorDialectResolver;

class MySqlVendorDialectResolverTests {
    @Test
    void recognizesMariaDbBehindCompatibilityPrefixWithoutInventingServerVersion() {
        var info = mock(DialectResolutionInfo.class);
        when(info.getDatabaseName()).thenReturn("MySQL");
        when(info.getDatabaseVersion()).thenReturn("5.5.5-10.4.32-MariaDB");
        var dialect = new MySqlVendorDialectResolver().resolveDialect(info);
        assertInstanceOf(MariaDBDialect.class,dialect);
        assertEquals(10,dialect.getVersion().getDatabaseMajorVersion());
        assertEquals(4,dialect.getVersion().getDatabaseMinorVersion());
        assertEquals(32,dialect.getVersion().getDatabaseMicroVersion());
    }

    @Test
    void nativeMysqlAndUnidentifiedServersKeepStandardResolution() {
        var info = mock(DialectResolutionInfo.class);
        when(info.getDatabaseName()).thenReturn("MySQL");
        for (String version : new String[] {"8.0.46","5.5.5","unknown-MariaDB"}) {
            when(info.getDatabaseVersion()).thenReturn(version);
            assertNull(new MySqlVendorDialectResolver().resolveDialect(info));
        }
    }
}
