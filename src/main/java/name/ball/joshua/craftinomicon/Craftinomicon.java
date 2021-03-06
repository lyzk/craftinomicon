package name.ball.joshua.craftinomicon;

import name.ball.joshua.craftinomicon.di.DI;
import name.ball.joshua.craftinomicon.di.Inject;
import name.ball.joshua.craftinomicon.recipe.*;
import name.ball.joshua.craftinomicon.recipe.i18n.MessageProvider;
import name.ball.joshua.craftinomicon.recipe.i18n.Translation;
import name.ball.joshua.craftinomicon.recipe.metrics.Gauge;
import name.ball.joshua.craftinomicon.recipe.metrics.GaugeStat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.configuration.Configuration;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.HumanEntity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.permissions.Permission;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.mcstats.Metrics;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

public class Craftinomicon extends JavaPlugin {

    @Inject private CraftinomiconCommandExecutor craftinomiconCommandExecutor;
    @Inject private ItemMetaManipulator itemMetaManipulator;
    @Inject private MaterialDataSubstitutes materialDataSubstitutes;
    @Inject private RecipeBookChecker recipeBookChecker;
    @Inject private RecipeBrowser recipeBrowser;
    @Inject private RecipeSnapshot recipeSnapshot;
    @PermissionKey("craftinomicon.craft.book") private Permission craftingPermission;
    @Translation(value = "title", english = "Craftinomicon") String titleTranslation;

    public void onDisable() {
    }

    public void onEnable() {
//        new CraftinomiconTestRunner().runTests(); // todo: re-enable

        saveDefaultConfig();
        FileConfiguration config = getConfig();
        config.options().copyHeader(true);
        saveConfig();

        DIGetter diGetter = new DIGetter();
        final DI di = diGetter.getDI(config);
        di.injectMembers(this);

        try {
            Metrics metrics = new Metrics(this);
            for (Map.Entry<String, List<Metrics.Plotter>> entry : diGetter.plotters.entrySet()) {
                Metrics.Graph graph = metrics.createGraph(entry.getKey());
                for (Metrics.Plotter plotter : entry.getValue()) {
                    graph.addPlotter(plotter);
                }
                metrics.addGraph(graph);
            }
            Metrics.Graph version = metrics.createGraph("Version");
            version.addPlotter(new Metrics.Plotter(getDescription().getVersion()) {
                @Override
                public int getValue() {
                    return 1;
                }
            });
            metrics.addGraph(version);
            Metrics.Graph languageCodeGraph = metrics.createGraph("Language Code");
            languageCodeGraph.addPlotter(new Metrics.Plotter(getConfig().getString("language.code")) {
                @Override
                public int getValue() {
                    return 1;
                }
            });
            metrics.addGraph(languageCodeGraph);
            metrics.start();
        } catch (IOException e) {
            // well, not much we can do
        }

        final PluginManager pm = this.getServer().getPluginManager();

        final ItemStack recipeBookItem = new ItemStack(Material.BOOK);
        itemMetaManipulator.forItemStack(recipeBookItem).setDisplayName(titleTranslation);

        final ShapelessRecipe recipeBookRecipe = new ShapelessRecipe(recipeBookItem);
        recipeBookRecipe.addIngredient(Material.BOOK);
        recipeBookRecipe.addIngredient(Material.WORKBENCH);

        final Server server = Bukkit.getServer();

        class RecipeBookCraftingInterceptor implements Listener {
            @EventHandler
            public void convertToRecipeBook(PrepareItemCraftEvent event) {
                CraftingInventory inventory = event.getInventory();
                Recipe recipe = inventory.getRecipe();
                if (recipe instanceof ShapelessRecipe) {
                    ShapelessRecipe shapelessRecipe = (ShapelessRecipe) recipe;
                    List<ItemStack> ingredientList = shapelessRecipe.getIngredientList();
                    if (ingredientList.size() == 2 && ingredientList.get(1).getType().equals(Material.WORKBENCH)) { // todo: write a unit test that makes sure that BOOK always occurs before WORKBENCH
                        ItemStack firstIngredient = ingredientList.get(0);
                        if (Material.BOOK.equals(firstIngredient.getType()) && !recipeBookChecker.isRecipeBook(firstIngredient)) {
                            boolean hasPermissionToCraft = false;
                            for (HumanEntity viewer : event.getViewers()) {
                                if (viewer.hasPermission(craftingPermission)) {
                                    hasPermissionToCraft = true;
                                    break;
                                }
                            }
                            if (hasPermissionToCraft) {
                                inventory.setResult(recipeBookItem);
                            } else {
                                inventory.setResult(null);
                            }
                        }
                    }
                }
            }
        }
        pm.registerEvents(new RecipeBookCraftingInterceptor(), this);

        class RecipeBookConsumeEventHandler implements Listener {
            @EventHandler
            public void onConsumeRecipeBook(PlayerInteractEvent event) {
                Action action = event.getAction();
                switch (action) {
                    case RIGHT_CLICK_AIR:
                    case RIGHT_CLICK_BLOCK:
                        ItemStack itemInHand = event.getPlayer().getItemInHand();
                        if (recipeBookChecker.isRecipeBook(itemInHand)) {
                            recipeBrowser.showAllItems(event.getPlayer());
                        }
                }
            }
        }
        pm.registerEvents(new RecipeBookConsumeEventHandler(), this);

        getCommand("craftinomicon").setExecutor(craftinomiconCommandExecutor);

        // We don't want to construct the recipe index until after the other plugins have loaded and had a chance
        // to register their recipes.
        new BukkitRunnable() {
            @Override public void run() {
                server.addRecipe(recipeBookRecipe); // unlimitedrecipes plugin loads after craftinomicon, and calls Bukkit.resetRecipes(), so we need to add the recipe after unlimitedrecipes has loaded
                materialDataSubstitutes.initialize();
                recipeSnapshot.initialize();
                for (Object o : di.getAllKnownInstances()) {
                    if (o instanceof Listener) {
                        pm.registerEvents((Listener)o, Craftinomicon.this);
                    }
                }
            }
        }.runTask(this);
    }

    private class DIGetter {

        Map<String,List<Metrics.Plotter>> plotters = new LinkedHashMap<String, List<Metrics.Plotter>>();

        DI getDI(Configuration configuration) {
            Map<Class<?>, DI.Provider<?>> providers = new LinkedHashMap<Class<?>, DI.Provider<?>>();
            providers.put(Plugin.class, new DI.Provider<Plugin>() {
                @Override
                public Plugin get() {
                    return Craftinomicon.this;
                }
            });

            Locale locale = Locale.getDefault();
            String languageCode = configuration.getString("language.code");
            if (languageCode != null && !"server".equals(languageCode)) {
                try {
                    if (!languageCode.contains("_")) {
                        locale = new Locale(languageCode);
                    } else {
                        int i = languageCode.indexOf('_');
                        locale = new Locale(languageCode.substring(0, i), languageCode.substring(i+1));
                    }
                } catch (IllegalArgumentException e) {
                    // this never fires?
                    getLogger().warning("Unrecognized locale '" + languageCode + "'. Falling back to server default '" + locale.getLanguage() + "'");
                }
            }

            final MessageProvider messageProvider = new MessageProvider(locale);
            DI.DIVisitor visitor = new DI.DIVisitor() {
                @Override
                public void visitField(DI.DIField diField) {
                    Field field = diField.getField();
                    if (field.isAnnotationPresent(Translation.class)) {
                        Translation annotation = field.getAnnotation(Translation.class);
                        diField.setValue(messageProvider.getMessage(field.getType(), annotation.value(), annotation.english()));
                    } else if (field.isAnnotationPresent(PermissionKey.class)) {
                        String permissionName = field.getAnnotation(PermissionKey.class).value();
                        for (Permission permission : getDescription().getPermissions()) {
                            if (permission.getName().equals(permissionName)) {
                                diField.setValue(permission);
                                return;
                            }
                        }
                        throw new IllegalArgumentException("unrecognized permission: " + permissionName);
                    } else if (field.isAnnotationPresent(Gauge.class)) {
                        class GaugePlotter extends Metrics.Plotter implements GaugeStat {

                            public GaugePlotter(String name) {
                                super(name);
                            }

                            private int value = 0;

                            @Override
                            public void set(int stat) {
                                value = stat;
                            }

                            @Override
                            public int getValue() {
                                return value;
                            }
                        }
                        Gauge annotation = field.getAnnotation(Gauge.class);
                        String graph = annotation.graph();
                        if (!plotters.containsKey(graph)) {
                            plotters.put(graph, new ArrayList<Metrics.Plotter>());
                        }
                        GaugePlotter gaugePlotter = new GaugePlotter(annotation.plotter());
                        diField.setValue(gaugePlotter);
                        plotters.get(graph).add(gaugePlotter);
                    }
                }
            };
            return new DI(visitor, providers);
        }

    }

}
