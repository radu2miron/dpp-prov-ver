package edu.tucn.dppprovver.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;

class ReadOnlyLedgerServiceTest {
  @Test void nonzeroHistoricalPositionIsRejectedBeforeGatewayInvocation() {
    var gateway=mock(BlockchainProxy.class);
    var service=new ReadOnlyLedgerService(gateway,
            new JsonMapper(new ObjectMapper().findAndRegisterModules()));
    assertThrows(IllegalArgumentException.class,
            ()->service.readState("dpp", "2026-10-20T11:30:00Z","1"));
    verifyNoInteractions(gateway);
  }
}
