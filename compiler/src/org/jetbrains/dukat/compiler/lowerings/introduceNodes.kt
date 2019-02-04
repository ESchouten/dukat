package org.jetbrains.dukat.compiler.lowerings

import org.jetbrains.dukat.ast.model.duplicate
import org.jetbrains.dukat.ast.model.nodes.AnnotationNode
import org.jetbrains.dukat.ast.model.nodes.ClassLikeNode
import org.jetbrains.dukat.ast.model.nodes.ClassNode
import org.jetbrains.dukat.ast.model.nodes.ConstructorNode
import org.jetbrains.dukat.ast.model.nodes.DocumentRootNode
import org.jetbrains.dukat.ast.model.nodes.FunctionNode
import org.jetbrains.dukat.ast.model.nodes.InterfaceNode
import org.jetbrains.dukat.ast.model.nodes.MethodNode
import org.jetbrains.dukat.ast.model.nodes.ObjectNode
import org.jetbrains.dukat.ast.model.nodes.PropertyNode
import org.jetbrains.dukat.ast.model.nodes.VariableNode
import org.jetbrains.dukat.astCommon.MemberDeclaration
import org.jetbrains.dukat.astCommon.TopLevelDeclaration
import org.jetbrains.dukat.compiler.converters.convertIndexSignatureDeclaration
import org.jetbrains.dukat.compiler.converters.convertMethodSignatureDeclaration
import org.jetbrains.dukat.compiler.converters.convertPropertyDeclaration
import org.jetbrains.dukat.compiler.model.ROOT_CLASS_DECLARATION
import org.jetbrains.dukat.tsmodel.CallSignatureDeclaration
import org.jetbrains.dukat.tsmodel.ClassDeclaration
import org.jetbrains.dukat.tsmodel.ConstructorDeclaration
import org.jetbrains.dukat.tsmodel.DocumentRootDeclaration
import org.jetbrains.dukat.tsmodel.FunctionDeclaration
import org.jetbrains.dukat.tsmodel.InterfaceDeclaration
import org.jetbrains.dukat.tsmodel.MethodSignatureDeclaration
import org.jetbrains.dukat.tsmodel.ModifierDeclaration
import org.jetbrains.dukat.tsmodel.PropertyDeclaration
import org.jetbrains.dukat.tsmodel.VariableDeclaration
import org.jetbrains.dukat.tsmodel.types.FunctionTypeDeclaration
import org.jetbrains.dukat.tsmodel.types.IndexSignatureDeclaration
import org.jetbrains.dukat.tsmodel.types.ObjectLiteralDeclaration
import org.jetbrains.dukat.tsmodel.types.ParameterValueDeclaration
import org.jetbrains.dukat.tsmodel.types.TypeDeclaration

private fun hasDefaultModifier(modifiers: List<ModifierDeclaration>): Boolean {
    return modifiers.contains(ModifierDeclaration.DEFAULT_KEYWORD)
}

private fun FunctionDeclaration.isStatic() = modifiers.contains(ModifierDeclaration.STATIC_KEYWORD)

private fun CallSignatureDeclaration.convert(owner: ClassLikeNode): MethodNode {
    return MethodNode(
            "invoke",
            parameters,
            type,
            typeParameters,
            owner,
            false,
            false,
            true,
            listOf(AnnotationNode("nativeInvoke", emptyList())),
            true
    )
}

private fun ParameterValueDeclaration.convertNullable(): ParameterValueDeclaration {
    return when (this) {
        is TypeDeclaration -> copy(nullable = true)
        is FunctionTypeDeclaration -> copy(nullable = true)
        else -> duplicate()
    }
}

private fun ClassDeclaration.convert(): ClassNode {
    return ClassNode(
            name,
            members,
            typeParameters,
            parentEntities,
            null
    )
}

private fun InterfaceDeclaration.convert(): InterfaceNode {
    return InterfaceNode(
            name,
            members,
            typeParameters,
            parentEntities
    )
}

private fun ConstructorDeclaration.convert(owner: ClassLikeNode): ConstructorNode {
    return ConstructorNode(
            parameters,
            typeParameters
    )
}

private fun FunctionDeclaration.convert(): FunctionNode {
    val annotations = mutableListOf<AnnotationNode>()

    if (hasDefaultModifier(modifiers)) {
        annotations.add(AnnotationNode("JsName", listOf("default")))
    }

    return FunctionNode(
            name,
            parameters,
            type,
            typeParameters,
            mutableListOf(),
            annotations
    )
}


private class LowerDeclarationsToNodes {
    fun lowerMemberDeclaration(declaration: MemberDeclaration, owner: ClassLikeNode): List<MemberDeclaration> {
        return when (declaration) {
            is FunctionDeclaration -> listOf(MethodNode(
                    declaration.name,
                    declaration.parameters,
                    declaration.type,
                    declaration.typeParameters,
                    owner,
                    declaration.isStatic(),
                    false,
                    false,
                    emptyList(),
                    true
            ))
            is MethodSignatureDeclaration -> listOf(convertMethodSignatureDeclaration(declaration, owner))
            is CallSignatureDeclaration -> listOf(declaration.convert(owner))
            is PropertyDeclaration -> listOf(convertPropertyDeclaration(declaration, owner))
            is IndexSignatureDeclaration -> convertIndexSignatureDeclaration(declaration, owner)
            is ConstructorDeclaration -> listOf(declaration.convert(owner))
            else -> listOf(declaration)
        }
    }

    fun lowerInterfaceNode(declaration: InterfaceNode): InterfaceNode {
        return declaration.copy(
                members = declaration.members.flatMap { member -> lowerMemberDeclaration(member, declaration) }
        )
    }

    fun lowerClassNode(declaration: ClassNode): ClassNode {
        return declaration.copy(
                members = declaration.members.flatMap { member -> lowerMemberDeclaration(member, declaration) }
        )
    }

    fun lowerVariableDeclaration(declaration: VariableDeclaration): TopLevelDeclaration {
        return if (declaration.type is ObjectLiteralDeclaration) {
            //TODO: don't forget to create owner
            val objectNode = ObjectNode(
                    declaration.name,
                    (declaration.type as ObjectLiteralDeclaration).members.flatMap { member -> lowerMemberDeclaration(member, ROOT_CLASS_DECLARATION) },
                    mutableListOf()
            )


            objectNode.copy(members = objectNode.members.map {
                when (it) {
                    is PropertyNode -> it.copy(owner = objectNode, open = false)
                    is MethodNode -> it.copy(owner = objectNode, open = false)
                    else -> it
                }
            })
        } else {
            VariableNode(
                    declaration.name,
                    declaration.type,
                    mutableListOf()
            )
        }
    }

    fun lowerTopLevelDeclaration(declaration: TopLevelDeclaration): TopLevelDeclaration {
        return when (declaration) {
            is VariableDeclaration -> lowerVariableDeclaration(declaration)
            is FunctionDeclaration -> declaration.convert()
            is ClassDeclaration -> lowerClassNode(declaration.convert())
            is InterfaceDeclaration -> lowerInterfaceNode(declaration.convert())
            is DocumentRootDeclaration -> lowerDocumentRoot(declaration, null)
            else -> declaration
        }
    }

    fun lowerDocumentRoot(documenRoot: DocumentRootDeclaration, owner: DocumentRootNode?): DocumentRootNode {
        val declarations = documenRoot.declarations.map { declaration -> lowerTopLevelDeclaration(declaration) }

        val head = mutableListOf<TopLevelDeclaration>()
        val tail = mutableListOf<TopLevelDeclaration>()
        declarations.forEach { declaration ->
            if (declaration is DocumentRootNode) tail.add(declaration) else head.add(declaration)
        }

        val docRoot = DocumentRootNode(
                documenRoot.packageName,
                documenRoot.packageName,
                head + tail,
                null,
                mutableListOf()
        )


        docRoot.declarations.forEach { declaration ->
            if (declaration is DocumentRootNode) {
                declaration.owner = docRoot
            }
        }

        return docRoot
    }
}

fun DocumentRootDeclaration.introduceNodes() = LowerDeclarationsToNodes().lowerDocumentRoot(this, null)
