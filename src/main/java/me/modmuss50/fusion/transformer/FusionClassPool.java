package me.modmuss50.fusion.transformer;

import javassist.ClassPool;
import javassist.CtClass;
import javassist.NotFoundException;

public class FusionClassPool extends ClassPool {

    public FusionClassPool() {
        super(true);
    }

    @Override
    public CtClass get(String classname) {
        try {
            CtClass ctClass = super.get(classname);
            return ctClass;
        } catch (NotFoundException e){
            System.out.println("Skipping class load of " + classname);
            return null;
        }
    }
}
