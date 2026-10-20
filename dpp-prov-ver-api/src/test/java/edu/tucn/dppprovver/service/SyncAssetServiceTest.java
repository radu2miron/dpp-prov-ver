package edu.tucn.dppprovver.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.tucn.dppprovver.model.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
class SyncAssetServiceTest {
    @Test void sendsExactlyOneSynchronousSubmitWithRealAssetModel() {
       var gateway=mock(BlockchainProxy.class);
       when(gateway.execute(any())).thenReturn(new GatewayResult("","tx-asset","VALID",12));
       var service=new SyncAssetService(gateway,
               new JsonMapper(new ObjectMapper().findAndRegisterModules()));
       var asset=new Asset(); asset.setId("asset-01"); asset.setAssetType("MOTOR");
       var response=service.create(asset);
       assertEquals("asset-01",response.id());
       assertEquals("VALID",response.fabricStatus());
       var request=ArgumentCaptor.forClass(TransactionRequest.class);
       verify(gateway,times(1)).execute(request.capture());
       assertEquals("createAsset",request.getValue().function());
       assertTrue(request.getValue().args()[0].contains("\"assetType\":\"MOTOR\""));
    }
}
