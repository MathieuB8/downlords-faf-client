package com.faforever.client.player;

import com.faforever.client.fx.Controller;
import com.faforever.client.user.UserService;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.value.ChangeListener;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.inject.Inject;

@Component
@Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
public class UsernameChangeController implements Controller<Node> {
  private final UserService userService;
  public TextField usernameField;
  public VBox root;
  public Button changeButton;
  private Runnable callback;
  private ReadOnlyStringProperty displayPlayer;

  @Inject
  public UsernameChangeController(UserService userService) {
    this.userService = userService;
  }

  @Override
  public void initialize() {
    usernameField.textProperty().addListener((observable, oldValue, newValue) -> changeButton.setDisable(displayPlayer == null || newValue.equals(displayPlayer.get())));
  }

  public void setDisplayPlayer(ReadOnlyStringProperty displayPlayer) {
    this.displayPlayer = displayPlayer;
    ChangeListener<String> changeListener = (observable, oldValue, newValue) -> {
      if (!userService.getUsername().equals(newValue)) {
        root.setVisible(false);
        return;
      }
      usernameField.setText(newValue);
    };
    changeListener.changed(displayPlayer, null, displayPlayer.get());
    displayPlayer.addListener(changeListener);
  }

  @Override
  public Node getRoot() {
    return root;
  }


  public void onUsernameChangeRequested(ActionEvent actionEvent) {
    userService.changeUsername(usernameField.getText())
        .thenAccept(aVoid -> callback.run());
  }

  public void registerOnNameChangedCallback(Runnable callback) {
    this.callback = callback;
  }
}
