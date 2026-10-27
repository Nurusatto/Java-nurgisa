package com.fixflow.desktop;

import static org.junit.jupiter.api.Assertions.*;

import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;

class StartViewTest {
  @Test
  void bundledFxmlIsWellFormedAndTheStylesheetIsPresent() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    try (var source = getClass().getResourceAsStream("/fxml/start.fxml")) {
      assertNotNull(source);
      var document = factory.newDocumentBuilder().parse(source);
      assertEquals("BorderPane", document.getDocumentElement().getTagName());
    }
    assertNotNull(getClass().getResource("/css/fixflow.css"));
  }
}
