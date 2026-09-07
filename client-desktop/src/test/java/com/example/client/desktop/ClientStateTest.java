package com.example.client.desktop;

import com.example.client.desktop.net.ClientState;
import com.example.client.desktop.net.DesktopNetworkAdapter;
import com.example.common.dto.GatewayErrorResponse;
import com.example.common.dto.PongMessage;
import com.example.lobby.dto.CharacterListResponse;
import com.example.lobby.dto.CharacterSummary;
import com.example.lobby.dto.CreateCharacterResponse;
import com.example.lobby.dto.SelectCharacterResponse;
import com.example.login.dto.LoginResponse;
import com.example.world.dto.JoinWorldResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class ClientStateTest {

    private ClientState clientState;
    private DesktopNetworkAdapter mockAdapter;

    @BeforeEach
    void setUp() {
        clientState = new ClientState();
        mockAdapter = Mockito.mock(DesktopNetworkAdapter.class);
        clientState.setNetworkAdapter(mockAdapter);
    }

    @Test
    void testInitialState() {
        assertEquals(ClientState.AppPhase.DISCONNECTED, clientState.getPhase());
        assertNull(clientState.getAccountId());
        assertNull(clientState.getAuthToken());
        assertNull(clientState.getSelectedCharacterId());
        assertNull(clientState.getLastErrorMessage());
        assertTrue(clientState.getCharacterList().isEmpty());
    }

    @Test
    void testSendLoginTransitionsToAuthenticating() {
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        clientState.onStateChange(state -> listenerCalled.set(true));

        clientState.sendLogin("testUser", "testPass");

        assertEquals(ClientState.AppPhase.AUTHENTICATING, clientState.getPhase());
        assertNull(clientState.getLastErrorMessage());
        assertTrue(listenerCalled.get());
        verify(mockAdapter).sendLogin("testUser", "testPass");
    }

    @Test
    void testSuccessfulLoginResponse() {
        LoginResponse successResp = new LoginResponse(true, 42L, "mock-jwt-token", "");
        clientState.onLoginResponse(successResp);

        assertEquals(ClientState.AppPhase.LOBBY_CHAR_SELECT, clientState.getPhase());
        assertEquals(42L, clientState.getAccountId());
        assertEquals("mock-jwt-token", clientState.getAuthToken());
        assertNull(clientState.getLastErrorMessage());
        verify(mockAdapter).sendCharacterList(42L, "mock-jwt-token");
    }

    @Test
    void testFailedLoginResponse() {
        LoginResponse failResp = new LoginResponse(false, 0L, null, "Invalid credentials");
        clientState.onLoginResponse(failResp);

        assertEquals(ClientState.AppPhase.DISCONNECTED, clientState.getPhase());
        assertEquals("Invalid credentials", clientState.getLastErrorMessage());
    }

    @Test
    void testCharacterListResponse() {
        CharacterSummary c1 = new CharacterSummary(101L, "WarriorHero", 1, (short) 1, (short) 0, 100, 10, (short) 0, (short) 1, (short) 0);
        CharacterListResponse resp = new CharacterListResponse(42L, List.of(c1));

        clientState.onCharacterListResponse(resp);

        assertEquals(1, clientState.getCharacterList().size());
        assertEquals("WarriorHero", clientState.getCharacterList().get(0).name());
        assertEquals((short) 1, clientState.getCharacterList().get(0).raceId());
        assertEquals((short) 0, clientState.getCharacterList().get(0).gender());
    }

    @Test
    void testCreateCharacterResponseSuccess() {
        clientState.onCharacterCreateResponse(new CreateCharacterResponse(true, 202L, ""));

        assertEquals(ClientState.AppPhase.LOBBY_CHAR_SELECT, clientState.getPhase());
        assertNull(clientState.getLastErrorMessage());
    }

    @Test
    void testSelectCharacterAndJoinWorld() {
        clientState.onCharacterSelectResponse(new SelectCharacterResponse(true, 303L, ""));
        assertEquals(303L, clientState.getSelectedCharacterId());

        clientState.onJoinWorldResponse(new JoinWorldResponse(true, 230, "127.0.0.1", 9000, ""));
        assertEquals(ClientState.AppPhase.IN_WORLD, clientState.getPhase());
        assertEquals(230, clientState.getCurrentZoneId());
    }

    @Test
    void testPongAndLatencyCalculation() {
        long sendTime = System.currentTimeMillis() - 45;
        AtomicBoolean listenerCalled = new AtomicBoolean(false);
        clientState.setPongListener(p -> listenerCalled.set(true));

        clientState.onPongResponse(new PongMessage(sendTime, "ping-test"));

        assertTrue(clientState.getCurrentLatencyMs() >= 40);
        assertTrue(listenerCalled.get());
    }

    @Test
    void testGatewayErrorResponse() {
        GatewayErrorResponse err = new GatewayErrorResponse((short) 1001, (short) 1, (short) 1, "Session expired");
        clientState.onErrorResponse(err);

        assertEquals("Session expired", clientState.getLastErrorMessage());
        assertEquals(ClientState.AppPhase.DISCONNECTED, clientState.getPhase());
    }

    @Test
    void testReset() {
        clientState.onLoginResponse(new LoginResponse(true, 42L, "token", ""));
        clientState.reset();

        assertEquals(ClientState.AppPhase.DISCONNECTED, clientState.getPhase());
        assertNull(clientState.getAccountId());
        assertNull(clientState.getAuthToken());
    }
}
