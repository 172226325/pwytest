package com.pwy.apt.annotation;

import com.google.auto.service.AutoService;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * apt核心类，项目rebuild之后就可以看到自动生成的代码了
 *
 * 生成的java源代码会在target\generated-sources\annotations
 * 在idea中配置annotation processor之后，应该会将这个目录下生成的源代码一起编译
 *
 */
//AutoService注解用来自动生成META-INF/services/javax.annotation.processing.Processor
@AutoService(Processor.class)
public class FactoryProcessor extends AbstractProcessor {

    private Types typeUtils;
    private Messager messager;
    private Filer filer;
    private Elements elementUtils;
    private Map<String, FactoryGroupedClasses> factoryClasses = new LinkedHashMap<>();


    /**
     * 初始化处理器
     *
     * ProcessingEnvironment是一个注解处理工具的集合。它包含了众多工具类。例如
     *   Filer可以用来编写新文件
     *   Messager可以用来打印错误信息
     *   Elements是一个可以处理Element的工具类
     *
     * @param processingEnvironment
     */
    @Override
    public synchronized void init(ProcessingEnvironment processingEnvironment) {
        super.init(processingEnvironment);
        typeUtils = processingEnvironment.getTypeUtils();
        messager = processingEnvironment.getMessager();
        filer = processingEnvironment.getFiler();
        elementUtils = processingEnvironment.getElementUtils();
    }

    /**
     * 在返回的集合中指明要处理的注解类型的名称(这里必须是完整的包名+类名，例如com.example.annotation.Factory)
     * @return
     */
    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> annotations = new LinkedHashSet<>();
        info("CanonicalName is %s", Factory.class.getCanonicalName());
        annotations.add(Factory.class.getCanonicalName());
        return annotations;
    }


    /**
     * 返回值表示注解是否由当前Processor处理
     * 在这个方法的方法体中：
     *   我们可以校验被注解的对象是否合法
     *   可以编写处理注解的代码
     *   以及自动生成需要的java文件等
     * @param annotations
     * @param roundEnv
     * @return
     */
    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            //	扫描所有被@Factory注解的元素
            for (Element annotatedElement : roundEnv.getElementsAnnotatedWith(Factory.class)) {

                // 如果不是一个类元素，那么就抛出异常，终止编译
                if (annotatedElement.getKind() != ElementKind.CLASS) {
                    throw new ProcessingException(annotatedElement, "Only classes can be annotated with @%s",
                            Factory.class.getSimpleName());
                }

                // We can cast it, because we know that it of ElementKind.CLASS
                TypeElement typeElement = (TypeElement) annotatedElement;

                //基于面向对象的思想，我们可以将annotatedElement中包含的信息封装成一个对象，方便后续使用
                FactoryAnnotatedClass annotatedClass = new FactoryAnnotatedClass(typeElement);

                /**
                 * 为了生成合乎要求的ShapeFactory类，在生成ShapeFactory代码前需要对被Factory注解的元素进行一系列的校验
                 * ，只有通过校验，符合要求了才可以生成ShapeFactory代码，规则为：
                 *   1.只有类才能被@Factory注解。因为在ShapeFactory中我们需要实例化Shape对象
                 *     ，虽然@Factory注解声明了Target为ElementType.TYPE，但接口和枚举并不符合我们的要求。
                 *   2.被@Factory注解的类中需要有public的构造方法，这样才能实例化对象。
                 *   3.被注解的类必须是type指定的类的子类
                 *   4.id需要为String类型，并且需要在相同type组中唯一
                 *   5.具有相同type的注解类会被生成在同一个工厂类中
                 */
                checkValidClass(annotatedClass);

                //校验通过
                //开始处理注解信息来生成所需代码

                //本着面向对象的思想，我们还需声明FactoryGroupedClasses来封装从FactoryAnnotatedClass获取的信息
                FactoryGroupedClasses factoryClass = factoryClasses.get(annotatedClass.getQualifiedFactoryGroupName());
                if (factoryClass == null) {
                    String qualifiedGroupName = annotatedClass.getQualifiedFactoryGroupName();
                    factoryClass = new FactoryGroupedClasses(qualifiedGroupName);
                    factoryClasses.put(qualifiedGroupName, factoryClass);
                }

                // Checks if id is conflicting with another @Factory annotated class with the same id
                factoryClass.add(annotatedClass);
            }

            // 根据注解信息来生成ShapeFactory类
            for (FactoryGroupedClasses factoryClass : factoryClasses.values()) {
                //执行代码生成操作
                //由于直接使用Filer需要我们手动拼接类的代码，很可能一不小心写错了一个字母就致使所生成的类是无效的。
                //所以我们使用square公司的JavaPoet库
                //JavaPoet可以用对象的方式来帮助我们生成类代码
                //，也就是我们能只要把要生成的类文件包装成一个对象，JavaPoet便可以自动帮我们生成类文件了。

                factoryClass.generateCode(elementUtils, filer);
            }
            factoryClasses.clear();
        } catch (ProcessingException e) {
            error(e.getElement(), e.getMessage());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return true;
    }

    /**
     * 指定当前正在使用的Java版本
     * @return
     */
    @Override
    public SourceVersion getSupportedSourceVersion() {
        return super.getSupportedSourceVersion();
    }







    /**
     * Checks if the annotated element observes our rules
     */
    private void checkValidClass(FactoryAnnotatedClass item) throws ProcessingException {

        // Cast to TypeElement, has more type specific methods
        TypeElement classElement = item.getTypeElement();

        if (!classElement.getModifiers().contains(Modifier.PUBLIC)) {
            throw new ProcessingException(classElement, "The class %s is not public.",
                    classElement.getQualifiedName().toString());
        }

        // Check if it's an abstract class
        if (classElement.getModifiers().contains(Modifier.ABSTRACT)) {
            throw new ProcessingException(classElement,
                    "The class %s is abstract. You can't annotate abstract classes with @%",
                    classElement.getQualifiedName().toString(), Factory.class.getSimpleName());
        }

        // Check inheritance: Class must be child class as specified in @Factory.type();
        TypeElement superClassElement = elementUtils.getTypeElement(item.getQualifiedFactoryGroupName());
        if (superClassElement.getKind() == ElementKind.INTERFACE) {
            // Check interface implemented
            if (!classElement.getInterfaces().contains(superClassElement.asType())) {
                throw new ProcessingException(classElement,
                        "The class %s annotated with @%s must implement the interface %s",
                        classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
                        item.getQualifiedFactoryGroupName());
            }
        } else {
            // Check subclassing
            TypeElement currentClass = classElement;
            while (true) {
                /**
                 * getSuperclass()
                 * Returns the direct superclass of this type element.
                 * If this type element represents an interface or the class java.lang.Object,
                 * then a NoType with kind NONE is returned.
                 */
                TypeMirror superClassType = currentClass.getSuperclass();

                if (superClassType.getKind() == TypeKind.NONE) {
                    // Basis class (java.lang.Object) reached, so exit
                    throw new ProcessingException(classElement,
                            "The class %s annotated with @%s must inherit from %s",
                            classElement.getQualifiedName().toString(), Factory.class.getSimpleName(),
                            item.getQualifiedFactoryGroupName());
                }

                if (superClassType.toString().equals(item.getQualifiedFactoryGroupName())) {
                    // Required super class found
                    break;
                }

                // Moving up in inheritance tree
                currentClass = (TypeElement) typeUtils.asElement(superClassType);
            }
        }

        // Check if an empty public constructor is given
        for (Element enclosed : classElement.getEnclosedElements()) {
            if (enclosed.getKind() == ElementKind.CONSTRUCTOR) {
                ExecutableElement constructorElement = (ExecutableElement) enclosed;
                if (constructorElement.getParameters().size() == 0 &&
                        constructorElement.getModifiers().contains(Modifier.PUBLIC)) {
                    // Found an empty constructor
                    return;
                }
            }
        }

        // No empty constructor found
        throw new ProcessingException(classElement,
                "The class %s must provide an public empty default constructor",
                classElement.getQualifiedName().toString());
    }

    private void error(Element e, String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }

    private void error(String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args));
    }

    private void info(String msg, Object... args) {
        messager.printMessage(Diagnostic.Kind.NOTE, String.format(msg, args));
    }

}
