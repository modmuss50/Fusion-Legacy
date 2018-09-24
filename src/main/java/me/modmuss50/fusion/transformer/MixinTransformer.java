package me.modmuss50.fusion.transformer;

import me.modmuss50.fusion.MixinManager;
import me.modmuss50.fusion.api.*;
import javassist.*;
import javassist.bytecode.AccessFlag;
import javassist.bytecode.Descriptor;
import javassist.bytecode.InnerClassesAttribute;
import net.minecraft.launchwrapper.IClassTransformer;
import org.apache.commons.lang3.Validate;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.io.IOException;
import java.util.List;

/**
 * This is where most of it happens.
 */
public class MixinTransformer implements IClassTransformer {

    public static ClassPool cp = new ClassPool(true);

    public byte[] transform(String name, byte[] basicClass) {
        if (MixinManager.mixinTargetMap.containsKey(name)) {
            //This should not happen, just stop it from doing it anyway.
            if (MixinManager.transformedClasses.contains(name)) {
                MixinManager.logger.trace("Skipping mixin transformer as the transformer has already transformed this class");
                return basicClass;
            }
            MixinManager.transformedClasses.add(name);

            long start = System.currentTimeMillis();
            //makes a CtClass out of the byte array
            ClassPath tempCP = new ByteArrayClassPath(name, basicClass);
            cp.insertClassPath(tempCP);
            CtClass target = null;
            try {
                target = cp.get(name);
            } catch (NotFoundException e) {
                e.printStackTrace();
                throw new RuntimeException("Failed to generate target infomation");
            }
            if (target.isFrozen()) {
                target.defrost();
            }

            List<String> mixins = MixinManager.mixinTargetMap.get(name);
            MixinManager.logger.info("Found " + mixins.size() + " mixins for " + name);
            for (String mixinClassName : mixins) {
                CtClass mixinClass = null;
                try {
                    //loads the mixin class
                    mixinClass = cp.get(mixinClassName);
                } catch (NotFoundException e) {
                    throw new RuntimeException(e);
                }
                try {
                    for (CtMethod method : mixinClass.getMethods()) {
                        if (method.hasAnnotation(Rewrite.class)) {
                            Rewrite annotation = (Rewrite) method.getAnnotation(Rewrite.class);
                            //Copy's the mixin method to a new method targeting the target
                            //This also renames the methord to contain the classname of the mixin
                            CtMethod generatedMethod = CtNewMethod.copy(method, mixinClass.getName().replace(".", "$") + "$" + method.getName(), target, null);
                            target.addMethod(generatedMethod);
                            CtBehavior targetMethod = null;
                            boolean isConstructor = method.getName().equals("<init>");
                            if (!isConstructor) {
                                String targetName = getSrgName(method);
                                if (!MixinManager.RUNTIME_DEOBF) {
                                    targetName = MixinManager.methodRemapper.apply(name.replaceAll("\\.", "/"), targetName, method.getSignature());
                                }
                                System.out.println("Target Name:" + targetName);
                                for (CtMethod methodCandidate : target.getMethods()) {
                                    if (methodCandidate.getName().equals(targetName) && methodCandidate.getSignature().equals(method.getSignature())) {
                                        targetMethod = methodCandidate;
                                        break;
                                    }
                                }
                            } else {
                                for (CtConstructor constructor : target.getConstructors()) {
                                    if (constructor.getSignature().equals(method.getSignature())) {
                                        targetMethod = constructor;
                                        break;
                                    }
                                }
                            }

                            if (targetMethod == null) {
                                MixinManager.logger.error("Could not find method to inject into");
                                throw new RuntimeException("Could not find method " + method.getName() + " to inject into " + name);
                            }
                            //This generates the one line of code that calls the new method that was just injected above

                            String src = null;
                            String mCall = Modifier.isStatic(method.getModifiers()) ? "" : "this.";
                            switch (annotation.returnBehaviour()) {
                                case NONE:
                                    src = mCall + mixinClass.getName().replace(".", "$") + "$" + method.getName() + "($$);";
                                    break;
                                case OBJECT_NONE_NULL:
                                    src = "Object mixinobj = " + mCall + generatedMethod.getName() + "($$);" + "if(mixinobj != null){return ($w)mixinobj;}";
                                    break;
                                case BOOL_TRUE:
                                    if (!method.getReturnType().getName().equals("boolean")) {
                                        throw new RuntimeException(method.getName() + " does not return a boolean");
                                    }
                                    Validate.isTrue(targetMethod instanceof CtMethod);
                                    if (((CtMethod) targetMethod).getReturnType().getName().equals("boolean")) {
                                        src = "if(" + mCall + generatedMethod.getName() + "($$)" + "){return true;}";
                                        break;
                                    }
                                    src = "if(" + mCall + generatedMethod.getName() + "($$)" + "){return;}";
                                    break;
                                default:
                                    src = mCall + mixinClass.getName().replace(".", "$") + "$" + method.getName() + "($$);";
                                    break;
                            }

                            //Adds it into the correct location
                            switch (annotation.behavior()) {
                                case START:
                                    targetMethod.insertBefore(src);
                                    break;
                                case END:
                                    targetMethod.insertAfter(src);
                                    break;
                                case REPLACE:
                                    targetMethod.setBody(src);
                                    break;
                            }

                        } else if (method.hasAnnotation(Inject.class)) {
                            //Just copys and adds the method stright into the target class
                            String methodName = getSrgName(method);
                            if (!MixinManager.RUNTIME_DEOBF) {
                                methodName = MixinManager.methodRemapper.apply(name.replaceAll("\\.", "/"), methodName, method.getSignature());
                            }
                            Inject inject = (Inject) method.getAnnotation(Inject.class);
                            CtMethod generatedMethod = CtNewMethod.copy(method, methodName, target, null);

                            try {
                                //Removes the existing method if it exists
                                String desc = Descriptor.ofMethod(generatedMethod.getReturnType(), generatedMethod.getParameterTypes());
                                CtMethod existingMethod = target.getMethod(method.getName(), desc);
                                if (existingMethod != null) {
                                    target.removeMethod(existingMethod);
                                }
                            } catch (NotFoundException e) {
                                //Do nothing
                            }

                            target.addMethod(generatedMethod);
                        }
                    }
                    for (CtField field : mixinClass.getFields()) {
                        //Copy's the field over
                        if (field.hasAnnotation(Inject.class)) {
                            CtField generatedField = new CtField(field, target);
                            target.addField(generatedField);
                        }
                        if (field.hasAnnotation(Ghost.class)) {
                            Ghost ghost = (Ghost) field.getAnnotation(Ghost.class);
                            if (ghost.stripFinal()) {
                                CtField targetField = target.getField(field.getName(), field.getSignature());
                                targetField.getFieldInfo().setAccessFlags(targetField.getModifiers() & ~Modifier.FINAL);
                            }
                        }
                    }
                    //Adds all the interfaces from the mixin class to the target
                    for (CtClass iface : mixinClass.getInterfaces()) {
                        target.addInterface(iface);
                    }
                    for (CtConstructor constructor : mixinClass.getConstructors()) {
                        if (constructor.hasAnnotation(Inject.class)) {
                            CtConstructor generatedConstructor = CtNewConstructor.copy(constructor, target, null);
                            target.addConstructor(generatedConstructor);
                        }
                    }
                } catch (NotFoundException | CannotCompileException | ClassNotFoundException e) {
                    throw new RuntimeException(e);
                }
                MixinManager.logger.info("Successfully applied " + mixinClassName + " to " + name);
            }
            //Removes the target class from the temp classpath
            cp.removeClassPath(tempCP);
            try {
                MixinManager.logger.info("Successfully applied " + mixins.size() + " mixins to " + name + " in " + (System.currentTimeMillis() - start) + "ms");
                return target.toBytecode();
            } catch (IOException | CannotCompileException e) {
                throw new RuntimeException(e);
            }
        }

        return basicClass;
    }

    //Name, desc
    private String getSrgName(CtMethod method) {
        if (method.hasAnnotation(SRGName.class)) {
            try {
                SRGName map = (SRGName) method.getAnnotation(SRGName.class);
                if (!map.value().isEmpty()) {
                    String value = map.value();
                    return value;
                }
            } catch (ClassNotFoundException e) {
                throw new RuntimeException("Failed to get srg name", e);
            }
        }
        throw new RuntimeException("Mixin method is missing SRGName");
    }

    @Override
    public byte[] transform(String name, String transformedName, byte[] bytes) {
        return transform(name, bytes);
    }

    public static ClassNode readClassFromBytes(byte[] bytes) {
        ClassNode classNode = new ClassNode();
        ClassReader classReader = new ClassReader(bytes);
        classReader.accept(classNode, 0);
        return classNode;
    }

    public static byte[] writeClassToBytes(ClassNode classNode) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        classNode.accept(writer);
        return writer.toByteArray();
    }

    public static CtClass makePublic(CtClass c) {
        for (CtField field : c.getDeclaredFields()) {
            field.setModifiers(makePublic(field.getModifiers()));
        }
        for (CtBehavior behavior : c.getDeclaredBehaviors()) {
            behavior.setModifiers(makePublic(behavior.getModifiers()));
        }
        InnerClassesAttribute attr = (InnerClassesAttribute) c.getClassFile().getAttribute(InnerClassesAttribute.tag);
        if (attr != null) {
            for (int i = 0; i < attr.tableLength(); i++) {
                attr.setAccessFlags(i, makePublic(attr.accessFlags(i)));
            }
        }
        return c;
    }

    private static int makePublic(int flags) {
        if (!AccessFlag.isPublic(flags)) {
            flags = AccessFlag.setPublic(flags);
        }
        return flags;
    }


}
