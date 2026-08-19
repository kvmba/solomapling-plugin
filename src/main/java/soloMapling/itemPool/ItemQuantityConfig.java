package soloMapling.itemPool;

import java.io.IOException;
import java.io.Reader;
import java.util.Map;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.Environment.PluginResources;


public class ItemQuantityConfig {
    public static class TierRange {
        public int min;
        public int max;
    }

    public static class ItemType {
        public Map<String, TierRange> tiers;
    }

    public Map<String, ItemType> itemQuantities;


    public static ItemQuantityConfig readYaml(String filePath) {
        try (Reader r = PluginResources.openReader(filePath)) {
            YamlReader reader = new YamlReader(r);
            return reader.read(ItemQuantityConfig.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }
}
