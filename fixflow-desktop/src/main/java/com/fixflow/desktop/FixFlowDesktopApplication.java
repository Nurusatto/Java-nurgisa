package com.fixflow.desktop;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class FixFlowDesktopApplication extends Application {
  private com.fixflow.desktop.controller.ShellController controller;

  @Override
  public void start(Stage stage) throws Exception {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
    var loader = new FXMLLoader(getClass().getResource("/fxml/start.fxml"));
    javafx.scene.Parent view = loader.load();
    controller = loader.getController();
    var scene = new Scene(view, 1280, 800);
    scene.getStylesheets().add(getClass().getResource("/css/fixflow.css").toExternalForm());
    stage.setTitle("FixFlow");
    stage.setMinWidth(800);
    stage.setMinHeight(560);
    stage.setScene(scene);
    stage.show();
  }

  @Override
  public void stop() {
    if (controller != null) controller.stop();
  }

  public static void main(String[] args) {
    java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ru-RU"));
    launch(args);
  }
}
