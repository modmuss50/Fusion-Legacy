package me.modmuss50.fusion;


import me.modmuss50.fusion.api.IMixinProvider;
import me.modmuss50.fusion.api.Mixin;
import org.apache.commons.lang3.Validate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.*;
import java.util.stream.StreamSupport;

public class MixinManager {

    public static List<String> mixinClassList = new ArrayList<>();
    public static HashMap<String, List<String>> mixinTargetMap = new HashMap<>();

    public static List<String> transformedClasses = new ArrayList<>();
    public static Logger logger = LogManager.getFormatterLogger("FusionMixin");

    //To be set by the mod using it //TODO make this work with more than one mod at a time
    public static TriFunction<String, String, String, String> methodRemapper;
    public static boolean RUNTIME_DEOBF;

    public static void findMixins() {
        ServiceLoader<IMixinProvider> serviceLoader = ServiceLoader.load(IMixinProvider.class);
        StreamSupport.stream(serviceLoader.spliterator(), false).forEach(iMixinProvider -> Arrays.stream(iMixinProvider.getMixins()).forEach(MixinManager::registerMixin));
    }

    public static void registerMixin(String mixinClass) {
        try {
            Class cla = Class.forName(mixinClass);
            registerMixin(cla);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Failed to find mixinclass", e);
        }
    }

    public static void registerMixin(Class mixinClass) {
        Mixin mixin = (Mixin) mixinClass.getAnnotation(Mixin.class);
        Validate.notNull(mixin);
        String target = mixin.value();
        Validate.notNull(target);
        Validate.isTrue(!target.isEmpty());
        registerMixin(mixinClass.getName(), target);
    }

    public static void registerMixinProvicer(Class<? extends IMixinProvider> providerClass) {
        try {
            IMixinProvider mixinProvider = providerClass.newInstance();
            Arrays.stream(mixinProvider.getMixins()).forEach(MixinManager::registerMixin);
        } catch (InstantiationException | IllegalAccessException e) {
            throw new RuntimeException("Failed to load mixin provider", e);
        }
    }

    public static void registerMixin(String mixinClass, String targetClass) {
        if (mixinClassList.contains(mixinClass)) {
            throw new RuntimeException("Mixin  + " + mixinClass + " already registered!");
        }
        mixinClassList.add(mixinClass);
        if (mixinTargetMap.containsKey(targetClass)) {
            mixinTargetMap.get(targetClass).add(mixinClass);
        } else {
            List<String> list = new ArrayList<>();
            list.add(mixinClass);
            mixinTargetMap.put(targetClass, list);
        }
    }
}
