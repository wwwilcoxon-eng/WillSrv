package dev.willsrv.xlogin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

final class Msg {

    private Msg() {
    }

    static Component prefijo() {
        return Component.text()
                .append(Component.text("xLogin ", NamedTextColor.GOLD))
                .append(Component.text("» ", NamedTextColor.GOLD))
                .build();
    }

    static Component linea(String mensaje) {
        return prefijo().append(Component.text(mensaje, NamedTextColor.GRAY));
    }

    static Component exito(String mensaje) {
        return linea(mensaje);
    }

    static Component error(String mensaje) {
        return linea(mensaje);
    }

    static Component info(String mensaje) {
        return linea(mensaje);
    }
}
