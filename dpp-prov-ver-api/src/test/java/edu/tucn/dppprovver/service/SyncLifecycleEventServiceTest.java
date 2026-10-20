package edu.tucn.dppprovver.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tucn.dppprovver.model.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SyncLifecycleEventServiceTest {
   @Test void semanticRejectionStillHasValidFabricCommit() {
      var gateway=mock(BlockchainProxy.class);
      var json=new JsonMapper(new ObjectMapper().findAndRegisterModules());
      var event=new LifecycleEvent();
      event.setId("test-event"); event.setOperationId("op-test-event");
      when(gateway.execute(any())).thenReturn(new GatewayResult(
            "{\"docType\":\"ACCEPTANCE_DECISION\",\"id\":\"decision:test-event\","
            +"\"eventId\":\"test-event\",\"outcome\":\"REJECTED\"}",
             "tx-1","VALID",123));
      var response=new SyncLifecycleEventService(gateway,json).submit(event);
      assertEquals("VALID",response.fabricStatus());
      assertEquals("REJECTED",response.semanticOutcome());
      assertEquals("tx-1",response.transactionId());
      var captured=ArgumentCaptor.forClass(TransactionRequest.class);
      verify(gateway).execute(captured.capture());
      assertEquals("submitEvent",captured.getValue().function());
   }
   @Test void requiresStableEventAndOperationIds() {
       var service=new SyncLifecycleEventService(mock(BlockchainProxy.class),
               new JsonMapper(new ObjectMapper().findAndRegisterModules()));
       assertThrows(IllegalArgumentException.class,()->service.submit(new LifecycleEvent()));
   }
}
