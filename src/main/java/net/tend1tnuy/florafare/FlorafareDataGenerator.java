package net.tend1tnuy.florafare;

import net.fabricmc.fabric.api.datagen.v1.DataGeneratorEntrypoint;
import net.fabricmc.fabric.api.datagen.v1.FabricDataGenerator;

/**
 * Entry point for the Fabric Data Generation system.
 * This class is used to register data providers for generating assets, tags,
 * recipes, and other data-driven content.
 */
public class FlorafareDataGenerator implements DataGeneratorEntrypoint {

    @Override
    public void onInitializeDataGenerator(FabricDataGenerator fabricDataGenerator) {
        // Register your data providers here (e.g., BlockTagProvider, RecipeProvider)
    }
}