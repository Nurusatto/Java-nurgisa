package com.fixflow.desktop;

import static org.junit.jupiter.api.Assertions.*;

import com.fixflow.desktop.api.ApiClient;
import com.fixflow.desktop.viewmodel.ListState;
import org.junit.jupiter.api.Test;

class ClientStateTest {
  @Test
  void changingSearchResetsPagination() {
    var state = new ListState();
    state.next();
    state.next();
    state.search("  laptop ");
    assertEquals("0", state.filters().get("page"));
    assertEquals("laptop", state.filters().get("q"));
    state.previous();
    assertEquals(0, state.page());
  }

  @Test
  void serverAddressRejectsCredentialsAndUnsupportedProtocols() {
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiClient.validateAddress("jdbc:postgresql://localhost/db"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiClient.validateAddress("http://user:secret@localhost"));
    assertThrows(
        IllegalArgumentException.class,
        () -> ApiClient.validateAddress("http://localhost/api?token=abc"));
    assertEquals("https", ApiClient.validateAddress("https://example.test").getScheme());
  }
}
