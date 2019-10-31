package org.jetbrains.dukat.tsmodel.types

import org.jetbrains.dukat.tsmodel.MemberDeclaration

data class ObjectLiteralDeclaration(
        val members: List<MemberDeclaration>,
        override var nullable: Boolean = false,
        override var meta: ParameterValueDeclaration? = null
) : ParameterValueDeclaration, MemberDeclaration

fun ObjectLiteralDeclaration.canBeJson(): Boolean {
    if (members.size != 1) {
        return false
    }

    if (members[0] !is IndexSignatureDeclaration) {
        return false
    }

    val indexSignatureDeclaration = members.get(0) as IndexSignatureDeclaration
    if (indexSignatureDeclaration.indexTypes.size != 1) {
        return false
    }

    val indexType = indexSignatureDeclaration.indexTypes.get(0)

    if (indexType.type !is TypeDeclaration) {
        return false
    }

    val typeDeclaration = indexType.type

    return (typeDeclaration.isSimpleType("string"))
            && (indexSignatureDeclaration.returnType.isSimpleType("any"))
}
