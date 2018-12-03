package com.bleacherreport.adaptergen

import com.bleacherreport.adaptergenanotations.Bind
import com.bleacherreport.adaptergenanotations.ViewHolder
import com.google.auto.service.AutoService
import com.squareup.kotlinpoet.*
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import java.io.File
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.Processor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.ExecutableType
import javax.lang.model.type.PrimitiveType


@AutoService(Processor::class)
class AdapterGenProcessor : AbstractProcessor() {

    override fun getSupportedAnnotationTypes(): MutableSet<String> {
        return mutableSetOf(ViewHolder::class.java.name, Bind::class.java.name)
    }

    override fun getSupportedSourceVersion(): SourceVersion {
        return SourceVersion.latestSupported()
    }

    override fun process(set: MutableSet<out TypeElement>?, roundEnv: RoundEnvironment?): Boolean {
        val adapterMap = HashMap<String, HashSet<String>>()
        val layoutResIdMap = HashMap<String, Int>()
        val methodArgumentMap = HashMap<Element, String>()

        roundEnv?.getElementsAnnotatedWith(Bind::class.java)?.forEach { element ->
            val executableType = element.asType() as ExecutableType
            val parameters = executableType.parameterTypes
            if (parameters.size != 2) {
                throw RuntimeException("@Bind method must take one Any and one Int")
            }
            val param1 = parameters[0]

            if(param1 is DeclaredType) {
                var name = (param1.asElement() as TypeElement).qualifiedName.toString()
                if(name == "java.lang.String") name = "kotlin.String"
                methodArgumentMap[element] = name
            } else {
                methodArgumentMap[element] = resolvePrimitiveType((param1 as PrimitiveType).toString())
            }

        }

        val argumentTypeMap = HashMap<String, String>()

        roundEnv?.getElementsAnnotatedWith(ViewHolder::class.java)?.forEach { element ->
            println(element.simpleName)
            val annotation = element.getAnnotation(ViewHolder::class.java)!!
            annotation.adapters.forEach { adapter ->
                if (!adapterMap.containsKey(adapter)) {
                    adapterMap[adapter] = HashSet()
                }

                adapterMap[adapter]?.add(getFullPath(element))
            }

            layoutResIdMap[getFullPath(element)] = annotation.layoutResId

            element.enclosedElements.forEach { enclosedElement ->
                methodArgumentMap[enclosedElement]?.let { argument ->
                    argumentTypeMap[getFullPath(element)] = argument
                }
            }
        }

        adapterMap.forEach { entry ->

            val builder = FileSpec.builder("com.bleacherreport.adaptergen", entry.key)

            val dataListName = entry.key + "DataList"
            builder.addType(getDataList(dataListName, entry.value, argumentTypeMap))

            val dataTargetName = entry.key + "DataTarget"
            builder.addType(getDataTarget(dataTargetName, dataListName))

            builder.addFunction(getAdapterKtx(entry.key, entry.value, layoutResIdMap, argumentTypeMap, dataListName))

            val kaptKotlinGeneratedDir = processingEnv.options[KAPT_KOTLIN_GENERATED_OPTION_NAME]
            builder.build().writeTo(File(kaptKotlinGeneratedDir, "${entry.key}.kt"))
        }

        return true
    }

    private fun getDataList(name: String, viewHolderTypes: Set<String>, argumentTypeMap: Map<String, String>): TypeSpec {
        val typeSpec = TypeSpec.classBuilder(name)
                .superclass(ClassName("com.bleacherreport.adaptergenandroid", "ScopedDataList"))

        val dataClassNames = mutableListOf<ClassName>()
        viewHolderTypes.forEach { viewHolderType ->
            val argumentClass = argumentTypeMap[viewHolderType]!!
            val argumentClassName = ClassName.bestGuess(argumentClass)
            dataClassNames.add(argumentClassName)
            val simpleClassName = argumentClassName.simpleName
            typeSpec.addFunction(FunSpec.builder("add")
                    .addParameter("model", argumentClassName)
                    .addStatement("listInternal.add(model)")
                    .build())

            typeSpec.addFunction(FunSpec.builder("addListOf$simpleClassName")
                    .addParameter("list", ClassName("kotlin.collections", "List")
                            .parameterizedBy(argumentClassName))
                    .addStatement("listInternal.addAll(list)")
                    .build())
        }

        typeSpec.addProperty(PropertySpec.builder("listClasses", ClassName("kotlin.collections", "List")
                .parameterizedBy(ClassName("", "Class")
                        .parameterizedBy(STAR)))
                    .initializer("listOf(%L)", dataClassNames.map { CodeBlock.of("%T::class.java", it) }.joinToCode())
                    .addModifiers(KModifier.PRIVATE)
                    .build())
                .build()

        typeSpec.addProperty(PropertySpec.builder("isDiffComparable", Boolean::class)
                    .addModifiers(KModifier.OVERRIDE)
                    .delegate(CodeBlock.builder()
                            .beginControlFlow("lazy")
                            .addStatement(
                                "listClasses.all { %T::class.java.isAssignableFrom(it) }",
                                ClassName("com.bleacherreport.adaptergenandroid", "DiffComparable")
                            )
                            .endControlFlow()
                            .build())
                    .build())
                .build()

        return typeSpec.build()
    }

    private fun getDataTarget(name: String, dataListName: String): TypeSpec {
        val typeSpec = TypeSpec.interfaceBuilder(name)
                .addSuperinterface(ClassName("com.bleacherreport.adaptergenandroid", "AdapterDataTarget")
                        .parameterizedBy(ClassName("", dataListName)))

        return typeSpec.build()
    }

    private fun getAdapterKtx(adapterName: String, viewHolderTypes: Set<String>, layoutResIdMap: Map<String, Int>,
                              argumentTypeMap: Map<String, String>, dataListName: String): FunSpec {
        val funSpec = FunSpec.builder("attach$adapterName")
                .receiver(ClassName("androidx.recyclerview.widget", "RecyclerView"))
                .returns(ClassName("com.bleacherreport.adaptergenandroid", "AdapterDataTarget")
                        .parameterizedBy(ClassName("", dataListName)))
                .addStatement("val adapter = %T(⇥", ClassName("com.bleacherreport.adaptergenandroid", "FunctionalAdapter")
                    .parameterizedBy(ClassName("", dataListName))
                )
                .addCode("%L,\n", getCreateViewHolderLambda(viewHolderTypes, layoutResIdMap))
                .addCode("%L,\n", getBindViewHolderLamda(viewHolderTypes, argumentTypeMap))
                .addCode("%L\n", getItemTypeLambda(viewHolderTypes, argumentTypeMap))
                .addStatement("⇤)")
                .addStatement("this.adapter = adapter")
                .addStatement("return adapter")
        return funSpec.build()
    }


    private fun getCreateViewHolderLambda(viewHolderTypes: Set<String>, layoutResIdMap: Map<String, Int>): CodeBlock {
        return buildCodeBlock {
            beginControlFlow("{ viewGroup, type ->")
            viewHolderTypes.forEachIndexed { index, viewHolderClass ->
                beginControlFlow("if (type == $index)")
                addStatement(
                    "val view = %T.from(viewGroup.context).inflate(%L, viewGroup, false)",
                    ClassName("android.view", "LayoutInflater"),
                    layoutResIdMap[viewHolderClass]
                )
                addStatement("return@FunctionalAdapter %T(view)", ClassName.bestGuess(viewHolderClass))
                endControlFlow()
            }
            addStatement("throw RuntimeException(%S)", "Type not found ViewHolder set.")
            endControlFlow()
        }
    }


    private fun getBindViewHolderLamda(viewHolderTypes: Set<String>, argumentTypeMap: Map<String, String>): CodeBlock {
        return buildCodeBlock {
            beginControlFlow("{ viewHolder, position, dataset ->")
            viewHolderTypes.forEach { viewHolderClass ->
                val viewHolderClassName = ClassName.bestGuess(viewHolderClass)
                beginControlFlow("if (viewHolder::class == %T::class)", viewHolderClassName)
                addStatement(
                    "(viewHolder as %T).bindModel(dataset[position] as %T, position)",
                    viewHolderClassName,
                    ClassName.bestGuess(argumentTypeMap.getValue(viewHolderClass))
                )
                endControlFlow()
            }
            endControlFlow()
        }
    }


    private fun getItemTypeLambda(viewHolderTypes: Set<String>, argumentTypeMap: Map<String, String>): CodeBlock {
        return buildCodeBlock {
            beginControlFlow("{ position, dataset ->")
            addStatement("val dataItem = dataset[position]")
            viewHolderTypes.forEachIndexed { index, viewHolderClass ->
                beginControlFlow(
                    "if (dataItem::class == %T::class)",
                    ClassName.bestGuess(argumentTypeMap.getValue(viewHolderClass))
                )
                addStatement("return@FunctionalAdapter $index")
                endControlFlow()
            }
            addStatement("return@FunctionalAdapter -1")
            endControlFlow()
        }
    }

    private fun getFullPath(element: Element): String {
        return if(element is TypeElement) {
            var enclosing = element
            while (enclosing.kind != ElementKind.PACKAGE) {
                enclosing = enclosing.enclosingElement
            }
            val packageElement = enclosing as PackageElement
            var path = packageElement.qualifiedName.toString() + "." + element.simpleName.toString()
            if(path == "java.lang.String") path = "kotlin.String"
            path
        } else {
            resolvePrimitiveType((element as PrimitiveType).toString())
        }
    }

    private fun resolvePrimitiveType(typeName: String): String {
        return when (typeName) {
            "boolean" -> "kotlin.Boolean"
            "int" -> "kotlin.Int"
            "long" -> "kotlin.Long"
            "float" -> "kotlin.Float"
            "double" -> "kotlin.Double"
            "short" -> "kotlin.Short"
            "byte" -> "kotlin.Byte"
            else -> typeName
        }
    }

    companion object {
        const val KAPT_KOTLIN_GENERATED_OPTION_NAME = "kapt.kotlin.generated"
    }
}