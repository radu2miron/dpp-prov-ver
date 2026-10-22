# DPP-Prov-Ver: Temporal Provenance, Causal Versioning, and Conflict Resolution for Digital Product Passports - Hyperledger Fabric Chaincode

This repository contains the chaincode for the dpp-prov-ver application, which is built on top of Hyperledger Fabric.
The chaincode is responsible for managing LifecycleEvents.

## Steps to build, deploy and run the chaincode
### 1. Build chaincode
1.1. Build dpp-prov-ver-chaincode:
```mvn clean install```

1.2. Copy ```target/dpp-prov-ver-chaincode-1.0.0.tar.gz``` to ```../fabric/test-network/dpp-prov-ver-chaincode-1.0.0.tar.gz```

### 2. Open terminal and run the following commands to set up the environment variables:
```
export CONTAINER_CLI_COMPOSE="docker compose"
cd ../fabric/test-network
export PATH=${PWD}/../bin:$PATH
export FABRIC_CFG_PATH=$PWD/../config/
peer version
```

### 3. Bring network up and create channel
```
./network.sh up -s couchdb
./network.sh createChannel -c dpp-prov-ver-channel
```

### 4. Install chaincode on peers
4.1. Install chaincode on Org1 peer
```
export CORE_PEER_TLS_ENABLED=true
export CORE_PEER_LOCALMSPID="Org1MSP"
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_ADDRESS=localhost:7051

peer lifecycle chaincode install dpp-prov-ver-chaincode-1.0.0.tar.gz
```

4.2. Install chaincode on Org2 peer
```
export CORE_PEER_LOCALMSPID="Org2MSP"
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org2.example.com/users/Admin@org2.example.com/msp
export CORE_PEER_ADDRESS=localhost:9051

peer lifecycle chaincode install dpp-prov-ver-chaincode-1.0.0.tar.gz
```

### 5. Approve chaincode definition
5.1. Setup chaincode environment variables
```
peer lifecycle chaincode queryinstalled
export CC_PACKAGE_ID=<REPLACE WITH PACKAGE ID, E.G. dpp-prov-ver-chaincode-1.0.0:69de748301770f6ef64b42aa6bb6cb291df20aa39542c3ef94008615704007f3>
```

5.2. Approve chaincode for Org2 - env. variables already setup for this org at previous step
```
peer lifecycle chaincode approveformyorg -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID dpp-prov-ver-channel --name dpp-prov-ver-chaincode --version 1.0 --package-id $CC_PACKAGE_ID --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"
```

5.3. Approve chaincode for Org1
```
export CORE_PEER_LOCALMSPID="Org1MSP"
export CORE_PEER_MSPCONFIGPATH=${PWD}/organizations/peerOrganizations/org1.example.com/users/Admin@org1.example.com/msp
export CORE_PEER_TLS_ROOTCERT_FILE=${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt
export CORE_PEER_ADDRESS=localhost:7051

peer lifecycle chaincode approveformyorg -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID dpp-prov-ver-channel --name dpp-prov-ver-chaincode --version 1.0 --package-id $CC_PACKAGE_ID --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem"
```

### 6. Commit chaincode
```
peer lifecycle chaincode checkcommitreadiness --channelID dpp-prov-ver-channel --name dpp-prov-ver-chaincode --version 1.0 --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" --output json
peer lifecycle chaincode commit -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --channelID dpp-prov-ver-channel --name dpp-prov-ver-chaincode --version 1.0 --sequence 1 --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt"
peer lifecycle chaincode querycommitted --channelID dpp-prov-ver-channel --name dpp-prov-ver-chaincode
```

### 7. Invoke/test chaincode
7.1 Create
```
peer chaincode invoke --waitForEvent --waitForEventTimeout 60s -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt" -c '{"function":"createActor","Args":["{\"docType\":\"ACTOR\",\"id\":\"tcrud-actor-01\",\"organizationId\":\"Org1MSP\",\"role\":\"SERVICE_PROVIDER\"}"]}'
```

7.2. Read
```
peer chaincode query -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode -c '{"function":"readActor","Args":["tcrud-actor-01"]}'
```

7.3. Update/read
```
peer chaincode invoke --waitForEvent --waitForEventTimeout 60s -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt" -c '{"function":"updateActor","Args":["{\"docType\":\"ACTOR\",\"id\":\"tcrud-actor-01\",\"organizationId\":\"Org1MSP\",\"role\":\"REPAIRER\"}"]}'
peer chaincode query -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode -c '{"function":"readActor","Args":["tcrud-actor-01"]}'
```

7.4. Get all
```
peer chaincode query -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode -c '{"function":"getAllActors","Args":[]}'
```

7.5. Shared ids test
```
peer chaincode invoke --waitForEvent --waitForEventTimeout 60s -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt" -c '{"function":"createActor","Args":["{\"docType\":\"ACTOR\",\"id\":\"tcrud-shared-id\",\"organizationId\":\"Org1MSP\",\"role\":\"AUDITOR\"}"]}'
peer chaincode invoke --waitForEvent --waitForEventTimeout 60s -o localhost:7050 --ordererTLSHostnameOverride orderer.example.com --tls --cafile "${PWD}/organizations/ordererOrganizations/example.com/orderers/orderer.example.com/msp/tlscacerts/tlsca.example.com-cert.pem" -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode --peerAddresses localhost:7051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org1.example.com/peers/peer0.org1.example.com/tls/ca.crt" --peerAddresses localhost:9051 --tlsRootCertFiles "${PWD}/organizations/peerOrganizations/org2.example.com/peers/peer0.org2.example.com/tls/ca.crt" -c '{"function":"createAsset","Args":["{\"docType\":\"ASSET\",\"id\":\"tcrud-shared-id\",\"externalId\":\"urn:serial:machine:P1\",\"assetType\":\"MACHINE\",\"identityPolicyRef\":\"policy-v1\"}"]}'
peer chaincode query -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode -c '{"function":"readActor","Args":["tcrud-shared-id"]}'
peer chaincode query -C dpp-prov-ver-channel -n dpp-prov-ver-chaincode -c '{"function":"readAsset","Args":["tcrud-shared-id"]}'
```

### 8. Stop and clean up
To stop the network and clean up the volumes (i.e. erase data), run:
```
./network.sh down
```
