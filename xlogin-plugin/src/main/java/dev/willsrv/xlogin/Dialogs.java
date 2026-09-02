package dev.willsrv.xlogin;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.action.DialogActionCallback;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

import java.util.List;

final class Dialogs {

    private static final ClickCallback.Options OPTIONS = ClickCallback.Options.builder().build();

    private Dialogs() {
    }

    static void showAuthDialog(XLoginPlugin plugin, Player player, boolean register, int attemptsLeft) {
        Component titulo = Component.text(register ? "Registro" : "Inicio de Sesión", NamedTextColor.GOLD);

        StringBuilder cuerpo = new StringBuilder();
        if (register) {
            cuerpo.append("Bienvenido a WillSrv, ").append(player.getName()).append(".\n")
                    .append("Crea una contraseña para proteger tu cuenta.");
        } else {
            cuerpo.append("Hola de nuevo, ").append(player.getName()).append(".\n")
                    .append("Ingresa tu contraseña para continuar.");
            if (attemptsLeft < plugin.getMaxAttempts()) {
                cuerpo.append("\nIntentos restantes: ").append(attemptsLeft);
            }
        }
        final String textoCuerpo = cuerpo.toString();

        ActionButton enviar = ActionButton.builder(Component.text(
                        register ? "Registrarse" : "Iniciar Sesión", NamedTextColor.GOLD))
                .tooltip(Component.text(register ? "Crear tu cuenta" : "Entrar al servidor", NamedTextColor.GRAY))
                .width(200)
                .action(DialogAction.customClick((DialogActionCallback) (response, audience) ->
                        plugin.getServer().getGlobalRegionScheduler().run(plugin, t ->
                                plugin.authManager().handleCommandInput(player, response.getText("password") == null
                                        ? "" : response.getText("password").trim())), OPTIONS))
                .build();

        ActionButton salir = ActionButton.builder(Component.text("Salir", NamedTextColor.RED))
                .tooltip(Component.text("Desconectarte del servidor", NamedTextColor.GRAY))
                .width(100)
                .action(DialogAction.customClick((DialogActionCallback) (response, audience) -> {
                    plugin.getServer().getGlobalRegionScheduler().run(plugin, t ->
                            player.kick(Component.text("Has salido del inicio de sesión.", NamedTextColor.GOLD)));
                }, OPTIONS))
                .build();

        Dialog dialogo = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(titulo)
                        .canCloseWithEscape(false)
                        .pause(false)
                        .body(List.of(
                                DialogBody.plainMessage(Component.text(textoCuerpo, NamedTextColor.GRAY))))
                        .inputs(List.of(DialogInput.text("password",
                                        Component.text("Contraseña", NamedTextColor.GOLD))
                                .maxLength(plugin.getMaxPasswordLength())
                                .build()))
                        .build())
                .type(DialogType.multiAction(List.of(enviar)).exitAction(salir).columns(1).build()));

        player.showDialog(dialogo);
    }
}
