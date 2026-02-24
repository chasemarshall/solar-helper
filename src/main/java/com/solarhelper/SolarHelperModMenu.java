package com.solarhelper;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.isxander.yacl3.api.ConfigCategory;
import dev.isxander.yacl3.api.Option;
import dev.isxander.yacl3.api.OptionDescription;
import dev.isxander.yacl3.api.YetAnotherConfigLib;
import dev.isxander.yacl3.api.controller.BooleanControllerBuilder;
import dev.isxander.yacl3.api.controller.IntegerSliderControllerBuilder;
import dev.isxander.yacl3.api.controller.StringControllerBuilder;
import net.minecraft.text.Text;

public class SolarHelperModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> {
            SolarHelperConfig config = SolarHelperConfig.get();
            SolarHelperConfig defaults = new SolarHelperConfig();

            return YetAnotherConfigLib.createBuilder()
                .title(Text.literal("Solar Helper Settings"))
                .category(ConfigCategory.createBuilder()
                    .name(Text.literal("Features"))
                    .tooltip(Text.literal("Toggle features on or off"))
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Auto Welcome"))
                        .description(OptionDescription.of(Text.literal("Automatically say 'welcome' when a player joins")))
                        .binding(defaults.welcomeEnabled, () -> config.welcomeEnabled, v -> config.welcomeEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Auto Sellall"))
                        .description(OptionDescription.of(Text.literal("Automatically run /sellall when inventory is full")))
                        .binding(defaults.sellallEnabled, () -> config.sellallEnabled, v -> config.sellallEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Math Solver"))
                        .description(OptionDescription.of(Text.literal("Automatically solve math challenges")))
                        .binding(defaults.mathSolverEnabled, () -> config.mathSolverEnabled, v -> config.mathSolverEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Word Unscrambler"))
                        .description(OptionDescription.of(Text.literal("Automatically unscramble word challenges")))
                        .binding(defaults.unscrambleEnabled, () -> config.unscrambleEnabled, v -> config.unscrambleEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Auto Type"))
                        .description(OptionDescription.of(Text.literal("Automatically type the answer for typing challenges")))
                        .binding(defaults.autoTypeEnabled, () -> config.autoTypeEnabled, v -> config.autoTypeEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Typos"))
                        .description(OptionDescription.of(Text.literal("Occasionally make typos when typing to look more human")))
                        .binding(defaults.typosEnabled, () -> config.typosEnabled, v -> config.typosEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .option(Option.<Boolean>createBuilder()
                        .name(Text.literal("Math Fuzz"))
                        .description(OptionDescription.of(Text.literal("Occasionally get math answers slightly wrong to look more human")))
                        .binding(defaults.mathFuzzEnabled, () -> config.mathFuzzEnabled, v -> config.mathFuzzEnabled = v)
                        .controller(BooleanControllerBuilder::create)
                        .build())
                    .build())
                .category(ConfigCategory.createBuilder()
                    .name(Text.literal("Delays"))
                    .tooltip(Text.literal("Adjust delay timings for actions"))
                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Welcome Min Delay"))
                        .description(OptionDescription.of(Text.literal("Minimum delay before sending welcome message (ms)")))
                        .binding(defaults.welcomeMinDelayMs, () -> config.welcomeMinDelayMs, v -> config.welcomeMinDelayMs = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(500, 10000).step(100)
                            .formatValue(v -> Text.literal(v + "ms")))
                        .build())
                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Welcome Max Delay"))
                        .description(OptionDescription.of(Text.literal("Maximum delay before sending welcome message (ms)")))
                        .binding(defaults.welcomeMaxDelayMs, () -> config.welcomeMaxDelayMs, v -> config.welcomeMaxDelayMs = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(500, 10000).step(100)
                            .formatValue(v -> Text.literal(v + "ms")))
                        .build())
                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Challenge Min Delay"))
                        .description(OptionDescription.of(Text.literal("Minimum delay before answering challenges (ms)")))
                        .binding(defaults.challengeMinDelayMs, () -> config.challengeMinDelayMs, v -> config.challengeMinDelayMs = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(500, 15000).step(100)
                            .formatValue(v -> Text.literal(v + "ms")))
                        .build())
                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Challenge Max Delay"))
                        .description(OptionDescription.of(Text.literal("Maximum delay before answering challenges (ms)")))
                        .binding(defaults.challengeMaxDelayMs, () -> config.challengeMaxDelayMs, v -> config.challengeMaxDelayMs = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(500, 15000).step(100)
                            .formatValue(v -> Text.literal(v + "ms")))
                        .build())
                    .option(Option.<Integer>createBuilder()
                        .name(Text.literal("Sellall Cooldown"))
                        .description(OptionDescription.of(Text.literal("Cooldown between automatic /sellall commands (ms)")))
                        .binding(defaults.sellallCooldownMs, () -> config.sellallCooldownMs, v -> config.sellallCooldownMs = v)
                        .controller(opt -> IntegerSliderControllerBuilder.create(opt).range(1000, 10000).step(100)
                            .formatValue(v -> Text.literal(v + "ms")))
                        .build())
                    .build())
                .category(ConfigCategory.createBuilder()
                    .name(Text.literal("API"))
                    .tooltip(Text.literal("Configure external API settings"))
                    .option(Option.<String>createBuilder()
                        .name(Text.literal("OpenRouter API Key"))
                        .description(OptionDescription.of(Text.literal("Optional. Used as a fallback when the local dictionary can't unscramble a word. Get a key at openrouter.ai")))
                        .binding(defaults.openRouterApiKey, () -> config.openRouterApiKey, v -> config.openRouterApiKey = v)
                        .controller(StringControllerBuilder::create)
                        .build())
                    .build())
                .save(config::save)
                .build()
                .generateScreen(parent);
        };
    }
}
