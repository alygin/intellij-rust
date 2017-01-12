package org.rust.lang.core.types

import com.intellij.openapi.project.Project
import org.rust.lang.core.psi.RustFunctionElement
import org.rust.lang.core.psi.RustTraitItemElement
import org.rust.lang.core.resolve.indexes.RustImplIndex

abstract class RustPrimitiveTypeBase : RustTypeBase() {

    override fun getNonStaticMethodsIn(project: Project): Sequence<RustFunctionElement> =
        RustImplIndex.findNonStaticMethodsFor(this, project)

    override fun getTraitsImplementedIn(project: Project): Sequence<RustTraitItemElement> =
        emptySequence()

    companion object {
        fun fromTypeName(name: String): RustPrimitiveTypeBase? {
            val integerKind = RustIntegerType.Kind.values().find { it.name == name }
            if (integerKind != null) return RustIntegerType(integerKind)

            val floatKind = RustFloatType.Kind.values().find { it.name == name }
            if (floatKind != null) return RustFloatType(floatKind)

            return when (name) {
                "bool" -> RustBooleanType
                "char" -> RustCharacterType
                "str" -> RustStringSliceType
                else -> null
            }
        }
    }
}
