package net.shaddii.smartsorter.datagen;


import net.fabricmc.fabric.api.client.datagen.v1.provider.FabricModelProvider;
import net.fabricmc.fabric.api.datagen.v1.FabricPackOutput;
import net.minecraft.client.data.models.BlockModelGenerators;
import net.minecraft.client.data.models.ItemModelGenerators;
import net.minecraft.client.data.models.model.ModelTemplates;
import net.shaddii.smartsorter.SmartSorter;


 //Model provider for SmartSorter - generates block states and item models
 //This is the proper way to handle models in Minecraft 1.21.9

public class SmartSorterModelProvider extends FabricModelProvider {
    
    public SmartSorterModelProvider(FabricPackOutput output) {
        super(output);
    }

    @Override
    public void generateBlockStateModels(BlockModelGenerators generator) {
        // Storage Controller - simple block with no rotation
        generator.createTrivialCube(SmartSorter.STORAGE_CONTROLLER_BLOCK);
        
        // Intake Block - has FACING property, needs rotation variants
        generator.createNonTemplateModelBlock(SmartSorter.INTAKE_BLOCK);

        // Output Probe - has FACING property, needs rotation variants
        generator.createNonTemplateModelBlock(SmartSorter.PROBE_BLOCK);

    }

    @Override
    public void generateItemModels(ItemModelGenerators generator) {
        // Linking Tool - standalone item (not a block)
        generator.generateFlatItem(SmartSorter.LINKING_TOOL, ModelTemplates.FLAT_ITEM);
        
        // Note: Block items (INTAKE_ITEM, PROBE_ITEM, STORAGE_CONTROLLER_ITEM) 
        // are automatically generated from their block models by the BlockStateModelGenerator
    }
}
