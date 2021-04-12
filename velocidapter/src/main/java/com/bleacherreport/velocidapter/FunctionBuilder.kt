package com.bleacherreport.velocidapter

import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.buildCodeBlock
import javax.lang.model.element.Element
import javax.lang.model.type.ExecutableType
import javax.lang.model.type.PrimitiveType
import javax.lang.model.type.TypeKind

interface BaseBindFunction {
    val element: Element
    val argumentType: String
    val functionName: String
        get() = element.simpleName.toString().split("(")[0]

    fun CodeBlock.Builder.createBindFunction(viewHolderClassName: String)
}

class ViewBindingFunction private constructor(
    override val element: Element,
    val bindingType: String,
    override val argumentType: String,
) : BaseBindFunction {

    override fun CodeBlock.Builder.createBindFunction(viewHolderClassName: String) {
        beginControlFlow(
            "if (typedViewHolder.dataType == %T::class)",
            ClassName.bestGuess(argumentType)
        )

        addStatement(
            "typedViewHolder.bind(dataset[position] as %T, position)",
            ClassName.bestGuess(argumentType),
        )

        addStatement("return@FunctionalAdapter")

        endControlFlow()
    }

    companion object {
        fun from(element: Element): ViewBindingFunction {
            val executableType = (element.asType() as ExecutableType)
            val parameters = executableType.parameterTypes

            val viewBindingParamName = executableType.viewBindingParamNameAtPosition(0)
                ?: throw VelocidapterException("@ViewHolderBind method ${element.methodFullName()} should be an extension method for a ViewBinding. $BIND_INSTRUCTION")

            val dataParamName = executableType.paramNameAtPosition(1)
                ?: throw VelocidapterException("@ViewHolderBind method ${element.methodFullName()} second param should be a data param. $BIND_INSTRUCTION")

            if (parameters.size > 2) {
                throw VelocidapterException("@ViewHolderBind method ${element.methodFullName()} has too many params. $BIND_INSTRUCTION")
            }

            return ViewBindingFunction(
                element = element,
                bindingType = viewBindingParamName,
                argumentType = dataParamName
            )
        }
    }
}


class ViewHolderBindFunction private constructor(
    override val element: Element,
    override val argumentType: String,
    val viewBindingType: String?,
    val hasPositionParameter: Boolean,
) :
    BaseBindFunction {

    override fun CodeBlock.Builder.createBindFunction(viewHolderClassName: String) {
        beginControlFlow("if (viewHolder::class == %T::class)", ClassName.bestGuess(viewHolderClassName))
        addStatement(
            "(viewHolder as %T).apply {",
            ClassName.bestGuess(viewHolderClassName),
        )

        if (viewBindingType != null) {
            addStatement(
                "  %T.bind(itemView).",
                ClassName.bestGuess(viewBindingType)
            )
        }

        addStatement(
            if (hasPositionParameter) {
                "   %N(dataset[position] as %T, position)"
            } else {
                "   %N(dataset[position] as %T)"
            },
            functionName,
            ClassName.bestGuess(argumentType)
        )
        addStatement(
            "} \n" +
                    "return@FunctionalAdapter"
        )
        endControlFlow()
    }

    companion object {
        fun from(element: Element, viewHolderName: String, bindingType: String): ViewHolderBindFunction {
            val executableType = (element.asType() as ExecutableType)

            var startingPosition = 0
            val viewBindingParamName = executableType.viewBindingParamNameAtPosition(startingPosition)
            if (viewBindingParamName != null) {
                if (viewBindingParamName != bindingType)
                    throw VelocidapterException("@Bind method $viewHolderName.${element.simpleName} viewBinding type was $viewBindingParamName but should be $bindingType. $VIEW_HOLDER_BIND_INSTRUCTION")
                startingPosition++
            }

            val dataParamName = executableType.paramNameAtPosition(startingPosition++)
                ?: throw VelocidapterException("@Bind method $viewHolderName.${element.simpleName} data param is required. $VIEW_HOLDER_BIND_INSTRUCTION")

            val hasPositionParam = executableType.parameterTypes
                .getOrNull(startingPosition++)
                ?.let {
                    if (it !is PrimitiveType || it.kind != TypeKind.INT)
                        throw VelocidapterException("@Bind method $viewHolderName.${element.simpleName} second parameter is not an Int. $VIEW_HOLDER_BIND_INSTRUCTION")
                    true
                } ?: false

            executableType.paramNameAtPosition(startingPosition)?.let {
                throw VelocidapterException("@Bind method $viewHolderName.${element.simpleName}$executableType has too many params = ${executableType.parameterTypes.size}. $VIEW_HOLDER_BIND_INSTRUCTION")
            }

            return ViewHolderBindFunction(element,
                argumentType = dataParamName,
                viewBindingType = viewBindingParamName,
                hasPositionParameter = hasPositionParam)
        }
    }
}

class FunctionName(
    val functionName: String,
) {
    companion object {
        fun from(element: Element) = FunctionName(element.simpleName.toString().split("(")[0])

        fun createFunctionFrom(
            viewHolders: Set<BaseViewHolderBuilder>,
            fetch: BaseViewHolderBuilder.() -> FunctionName?,
        ): CodeBlock {
            return buildCodeBlock {
                beginControlFlow("{ viewHolder ->")
                viewHolders.filterIsInstance<BindMethodViewHolderBuilder>().forEach { viewHolder ->
                    val functionName = viewHolder.fetch() ?: return@forEach
                    beginControlFlow(
                        "if (viewHolder::class == %T::class)",
                        ClassName.bestGuess(viewHolder.name)
                    )
                    addStatement("(viewHolder as %T).%N()",
                        ClassName.bestGuess(viewHolder.name),
                        functionName.functionName)
                    endControlFlow()
                }
                viewHolders.filterIsInstance<ClassViewHolderBuilder>().filter { it.fetch() != null }
                    .takeIf { it.isNotEmpty() }?.also { viewHolders ->
                        beginControlFlow(
                            "if (viewHolder::class == %T::class)",
                            ClassName.bestGuess(VIEW_HOLDER_VIEW_BINDING_FULL)
                        )
                        addStatement("val typedViewHolder = viewHolder as %T",
                            ClassName.bestGuess(VIEW_HOLDER_VIEW_BINDING_FULL)
                        )
                        viewHolders.forEachIndexed { index, viewHolder ->
                            beginControlFlowElseIf(
                                index,
                                "if (typedViewHolder.dataType == %T::class)",
                                ClassName.bestGuess(viewHolder.bindFunction.argumentType),
                            )
                            viewHolder.fetch()?.also {
                                addStatement("typedViewHolder.${it.functionName}()",
                                    ClassName.bestGuess(viewHolder.name))
                            }
                            endControlFlow()
                        }
                        endControlFlow()
                    }
                endControlFlow()
            }
        }
    }
}