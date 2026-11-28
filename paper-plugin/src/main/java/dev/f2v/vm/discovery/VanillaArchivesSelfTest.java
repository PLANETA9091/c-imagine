package dev.f2v.vm.discovery;

import java.nio.file.Path;

/**
 * Selftest: run from the server directory with NO -Df2v.vanillaJar. Asserts that the
 * client-only screen chain (ClothRequiresRestartScreen -> ConfirmScreen -> Screen) and the
 * vanilla-menu chain (CraftingRobitContainer -> CraftingMenu) resolve through the auto-resolver.
 * Usage: VanillaArchivesSelfTest <cloth-config.jar> <mekanism.jar>
 */
public final class VanillaArchivesSelfTest {
    private VanillaArchivesSelfTest() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: cloth.jar mekanism.jar");
        if (System.getProperty("f2v.vanillaJar") != null) throw new IllegalStateException("property must be unset");
        ModScanner.Profile cloth = ModScanner.scan(Path.of(args[0]));
        boolean screen = cloth.screens.stream().anyMatch(s ->
                s.screenClass != null && s.screenClass.endsWith("ClothRequiresRestartScreen"));
        ModScanner.Profile mek = ModScanner.scan(Path.of(args[1]));
        boolean menu = mek.menus.stream().anyMatch(m ->
                m.menuClass != null && m.menuClass.endsWith("CraftingRobitContainer"))
                || mek.screens.stream().anyMatch(s -> s.menuClasses.stream()
                        .anyMatch(c -> c.endsWith("CraftingRobitContainer")));
        System.out.println("ClothRequiresRestartScreen resolved as screen: " + screen);
        System.out.println("CraftingRobitContainer resolved as menu: " + menu);
        System.out.println("resolver table: " + VanillaArchives.clientSuperclass(
                "net/minecraft/client/gui/screens/ConfirmScreen"));
        if (!screen || !menu) {
            System.out.println("VanillaArchivesSelfTest FAILED");
            System.exit(1);
        }
        System.out.println("VanillaArchivesSelfTest OK");
    }
}
