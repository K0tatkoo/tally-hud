package dev.kotatko.tally.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.kotatko.tally.ui.SettingsScreen;

/** Gives Mod Menu a "configure" button. Only loaded when Mod Menu is installed. */
public final class ModMenuCompat implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return SettingsScreen::new;
	}
}
