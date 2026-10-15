package com.faforever.iceadapter;

import java.awt.*;
import java.net.URL;
import java.util.Optional;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@UtilityClass
@Slf4j
public class LogoUtils {

    private static final String NAME_LOGO = "faf-logo.png";

    public Image getLogo() {
        URL resource = LogoUtils.class.getClassLoader().getResource(NAME_LOGO);
        if (resource != null) {
            return Toolkit.getDefaultToolkit().createImage(resource);
        } else {
            log.error("File {} not found", NAME_LOGO);
            return null;
        }
    }

    public Optional<javafx.scene.image.Image> getLogoFx() {
        URL resource = LogoUtils.class.getClassLoader().getResource(NAME_LOGO);
        if (resource != null) {
            return Optional.of(new javafx.scene.image.Image(resource.toExternalForm()));
        } else {
            log.error("File {} not found", NAME_LOGO);
            return Optional.empty();
        }
    }
}
