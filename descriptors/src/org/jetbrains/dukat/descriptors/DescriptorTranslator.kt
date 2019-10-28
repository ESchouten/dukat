package org.jetbrains.dukat.descriptors

import org.jetbrains.dukat.astCommon.IdentifierEntity
import org.jetbrains.dukat.astCommon.NameEntity
import org.jetbrains.dukat.astCommon.shiftRight
import org.jetbrains.dukat.astModel.AnnotationModel
import org.jetbrains.dukat.astModel.ClassLikeModel
import org.jetbrains.dukat.astModel.ClassModel
import org.jetbrains.dukat.astModel.ConstructorModel
import org.jetbrains.dukat.astModel.EnumModel
import org.jetbrains.dukat.astModel.FunctionModel
import org.jetbrains.dukat.astModel.FunctionTypeModel
import org.jetbrains.dukat.astModel.HeritageModel
import org.jetbrains.dukat.astModel.InterfaceModel
import org.jetbrains.dukat.astModel.MethodModel
import org.jetbrains.dukat.astModel.ModuleModel
import org.jetbrains.dukat.astModel.ObjectModel
import org.jetbrains.dukat.astModel.ParameterModel
import org.jetbrains.dukat.astModel.PropertyModel
import org.jetbrains.dukat.astModel.SourceSetModel
import org.jetbrains.dukat.astModel.TopLevelModel
import org.jetbrains.dukat.astModel.TypeModel
import org.jetbrains.dukat.astModel.TypeParameterModel
import org.jetbrains.dukat.astModel.TypeValueModel
import org.jetbrains.dukat.astModel.VariableModel
import org.jetbrains.dukat.panic.raiseConcern
import org.jetbrains.dukat.translatorString.translate
import org.jetbrains.kotlin.backend.common.SimpleMemberScope
import org.jetbrains.kotlin.builtins.DefaultBuiltIns
import org.jetbrains.kotlin.builtins.KotlinBuiltIns.FQ_NAMES
import org.jetbrains.kotlin.builtins.createFunctionType
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.ClassConstructorDescriptor
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.ClassKind
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentDescriptor
import org.jetbrains.kotlin.descriptors.PackageFragmentProviderImpl
import org.jetbrains.kotlin.descriptors.PropertyDescriptor
import org.jetbrains.kotlin.descriptors.SourceElement
import org.jetbrains.kotlin.descriptors.TypeParameterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.descriptors.Visibility
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptorImpl
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.impl.ClassConstructorDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ModuleDependenciesImpl
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PackageFragmentDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertyGetterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.PropertySetterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.TypeParameterDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.js.resolve.JsPlatformAnalyzerServices.builtIns
import org.jetbrains.kotlin.load.kotlin.toSourceElement
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.DescriptorFactory
import org.jetbrains.kotlin.resolve.constants.StringValue
import org.jetbrains.kotlin.storage.LockBasedStorageManager
import org.jetbrains.kotlin.types.ErrorUtils
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.KotlinTypeFactory
import org.jetbrains.kotlin.types.LazyWrappedType
import org.jetbrains.kotlin.types.StarProjectionImpl
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.createDynamicType
import org.jetbrains.kotlin.types.isError

internal fun translateName(name: NameEntity): String {
    return name.translate().trim('`')
}

private class DescriptorTranslator(val context: DescriptorContext) {

    private fun findClassInStdlib(typeModel: TypeValueModel): ClassDescriptor? {
        val packageNames = context.registeredImports.map { FqName(it) }
        val stdlibModule = context.config.moduleDescriptors.first { it.name == Name.special("<kotlin>") }
        return packageNames.map { packageName ->
            val packageDescriptor = stdlibModule.getPackage(packageName)
            packageDescriptor.fragments.mapNotNull { fragment ->
                fragment.getMemberScope().getContributedClassifier(
                    Name.identifier(translateName(typeModel.value)),
                    NoLookupLocation.FROM_TEST
                )
            }
        }.flatten().filterIsInstance<ClassDescriptor>().firstOrNull()
    }

    private fun findClass(typeModel: TypeValueModel): ClassDescriptor? {
        return when (translateName(typeModel.value)) {
            "Any" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.any.toSafe())
            "Array" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.array.toSafe())
            "Boolean" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._boolean.toSafe())
            "Byte" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._byte.toSafe())
            "Char" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._char.toSafe())
            "Double" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._double.toSafe())
            "Float" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._float.toSafe())
            "Function" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.functionSupertype.toSafe())
            "Int" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._int.toSafe())
            "Long" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._long.toSafe())
            "List" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.list)
            "Nothing" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.nothing.toSafe())
            "Number" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.number.toSafe())
            "Short" -> builtIns.getBuiltInClassByFqName(FQ_NAMES._short.toSafe())
            "String" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.string.toSafe())
            "Suppress" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.suppress)
            "Unit" -> builtIns.getBuiltInClassByFqName(FQ_NAMES.unit.toSafe())
            else -> context.getDescriptor(typeModel.value) ?: findClassInStdlib(typeModel)
        }
    }

    private fun translateVariance(variance: org.jetbrains.dukat.astModel.Variance): Variance {
        return when (variance) {
            org.jetbrains.dukat.astModel.Variance.INVARIANT -> Variance.INVARIANT
            org.jetbrains.dukat.astModel.Variance.COVARIANT -> Variance.OUT_VARIANCE
            org.jetbrains.dukat.astModel.Variance.CONTRAVARIANT -> Variance.IN_VARIANCE
        }
    }

    private fun translateType(typeModel: TypeModel): KotlinType {
        if (typeModel is TypeValueModel) {
            val typeProjectionTypes = typeModel.params.map {
                if (it.type is TypeValueModel && (it.type as TypeValueModel).value == IdentifierEntity("*")) {
                    null
                } else {
                    translateType(it.type)
                }
            }
            return context.getTypeParameter(typeModel.value)?.defaultType?.makeNullableAsSpecified(typeModel.nullable)
                ?: LazyWrappedType(LockBasedStorageManager.NO_LOCKS) {
                    val classDescriptor = findClass(typeModel)
                    if (classDescriptor == null) {
                        if (typeModel.value == IdentifierEntity("dynamic")) {
                            createDynamicType(builtIns)
                        } else {
                            ErrorUtils.createErrorType(translateName(typeModel.value))
                                .makeNullableAsSpecified(typeModel.nullable)
                        }
                    } else {
                        KotlinTypeFactory.simpleType(
                            annotations = Annotations.EMPTY,
                            constructor = classDescriptor.defaultType.constructor,
                            arguments = typeModel.params.zip(typeProjectionTypes).mapIndexed { index, (model, projectionType) ->
                                if (model.type is TypeValueModel && (model.type as TypeValueModel).value == IdentifierEntity(
                                        "*"
                                    )
                                ) {
                                    StarProjectionImpl(classDescriptor.declaredTypeParameters[index])
                                } else {
                                    TypeProjectionImpl(
                                        translateVariance(model.variance),
                                        projectionType!!
                                    )
                                }
                            },
                            nullable = typeModel.nullable
                        )
                    }
                }
        }
        if (typeModel is FunctionTypeModel) {
            val parameterTypes = typeModel.parameters.map { translateType(it.type) }
            val returnType = translateType(typeModel.type)
            return LazyWrappedType(LockBasedStorageManager.NO_LOCKS) {
                createFunctionType(
                    builtIns = builtIns,
                    annotations = Annotations.EMPTY,
                    receiverType = null,
                    parameterTypes = parameterTypes,
                    parameterNames = typeModel.parameters.map { Name.identifier(it.name) },
                    returnType = returnType
                ).makeNullableAsSpecified(typeModel.nullable)
            }
        }
        return ErrorUtils.createErrorType("NOT_IMPLEMENTED")
    }

    private fun translateParameters(
        parameterModels: List<ParameterModel>,
        parent: FunctionDescriptor
    ): List<ValueParameterDescriptorImpl> {
        return parameterModels.mapIndexed { index, parameter ->
            val type = translateType(parameter.type)
            val outType = if (parameter.vararg) {
                builtIns.getPrimitiveArrayKotlinTypeByPrimitiveKotlinType(
                    type
                ) ?: builtIns.getArrayType(Variance.OUT_VARIANCE, type)
            } else {
                type
            }
            val varargElementType = if (parameter.vararg) {
                type
            } else {
                null
            }
            ValueParameterDescriptorImpl(
                containingDeclaration = parent,
                original = null,
                index = index,
                annotations = Annotations.EMPTY,
                name = Name.identifier(translateName(IdentifierEntity(parameter.name))),
                outType = outType,
                declaresDefaultValue = parameter.initializer != null,
                isCrossinline = false,
                isNoinline = false,
                varargElementType = varargElementType,
                source = parent.source
            )
        }
    }


    private fun translateTypeParameters(
        parameters: List<TypeParameterModel>,
        parent: DeclarationDescriptor
    ): List<TypeParameterDescriptor> {
        val parametersDescriptors = parameters.mapIndexed { index, parameter ->
            val parameterDescriptor = TypeParameterDescriptorImpl.createForFurtherModification(
                parent,
                Annotations.EMPTY,
                false,
                translateVariance(parameter.variance),
                Name.identifier(translateName((parameter.type as TypeValueModel).value)),
                index,
                parent.toSourceElement
            )
            context.registerTypeParameter(IdentifierEntity(parameterDescriptor.name.identifier), parameterDescriptor)
            parameterDescriptor
        }
        parametersDescriptors.zip(parameters).forEach { (descriptor, model) ->
            model.constraints.forEach {
                descriptor.addUpperBound(translateType(it))
            }
            descriptor.addDefaultUpperBound()
            descriptor.setInitialized()
        }
        return parametersDescriptors
    }

    private fun translateAnnotations(annotationModels: List<AnnotationModel>): Annotations {
        return Annotations.create(annotationModels.map { annotationModel ->
            val typeModel = TypeValueModel(
                value = IdentifierEntity(annotationModel.name),
                params = listOf(),
                metaDescription = null
            )
            val annotationClassDescriptor = findClass(typeModel)
            AnnotationDescriptorImpl(
                translateType(typeModel),
                annotationClassDescriptor?.constructors?.first()?.valueParameters?.map { it.name }?.zip(
                    annotationModel.params.map { StringValue(it.translate()) })?.toMap().orEmpty(),
                SourceElement.NO_SOURCE
            )
        })
    }

    private fun translateHeritage(heritageModel: HeritageModel): KotlinType? {
        val type = translateType(
            TypeValueModel(
                value = heritageModel.value.value,
                params = heritageModel.typeParams.map {
                    TypeParameterModel(
                        it,
                        listOf()
                    )
                },
                metaDescription = null
            )
        )
        return if (type.isError) null else type
    }

    private fun translateMethod(methodModel: MethodModel, parent: ClassDescriptor): FunctionDescriptor {
        val functionDescriptor = SimpleFunctionDescriptorImpl.create(
            parent,
            translateAnnotations(methodModel.annotations),
            Name.identifier(translateName(methodModel.name)),
            CallableMemberDescriptor.Kind.DECLARATION,
            SourceElement.NO_SOURCE
        )

        val typeParameters = translateTypeParameters(methodModel.typeParameters, functionDescriptor)
        functionDescriptor.initialize(
            null,
            null,
            typeParameters,
            translateParameters(methodModel.parameters, functionDescriptor),
            translateType(methodModel.type),
            when (parent.kind) {
                ClassKind.INTERFACE -> Modality.ABSTRACT
                ClassKind.OBJECT -> Modality.FINAL
                else -> Modality.OPEN
            },
            Visibilities.PUBLIC
        )
        functionDescriptor.isOperator = methodModel.operator

        typeParameters.forEach {
            context.removeTypeParameter(IdentifierEntity(it.name.identifier))
        }

        return functionDescriptor
    }

    private fun translateFunction(functionModel: FunctionModel, parent: PackageFragmentDescriptor): FunctionDescriptor {
        val functionDescriptor = SimpleFunctionDescriptorImpl.create(
            parent,
            translateAnnotations(functionModel.annotations),
            Name.identifier(translateName(functionModel.name)),
            CallableMemberDescriptor.Kind.DECLARATION,
            SourceElement.NO_SOURCE
        )
        val typeParameters = translateTypeParameters(functionModel.typeParameters, functionDescriptor)
        functionDescriptor.initialize(
            null,
            null,
            typeParameters,
            translateParameters(functionModel.parameters, functionDescriptor),
            translateType(functionModel.type),
            Modality.FINAL,
            Visibilities.PUBLIC
        )
        typeParameters.forEach {
            context.removeTypeParameter(IdentifierEntity(it.name.identifier))
        }
        functionDescriptor.isExternal = true
        return functionDescriptor
    }

    private fun translateConstructor(
        constructorModel: ConstructorModel,
        parent: ClassDescriptor,
        isPrimary: Boolean,
        visibility: Visibility
    ): ClassConstructorDescriptor {
        val constructorDescriptor = ClassConstructorDescriptorImpl.create(
            parent,
            Annotations.EMPTY,
            isPrimary,
            SourceElement.NO_SOURCE
        )
        constructorDescriptor.initialize(
            translateParameters(constructorModel.parameters, constructorDescriptor),
            visibility
        )
        constructorDescriptor.returnType = LazyWrappedType(LockBasedStorageManager.NO_LOCKS) {
            parent.defaultType
        }
        return constructorDescriptor
    }

    private fun translateProperty(propertyModel: PropertyModel, parent: ClassDescriptor): PropertyDescriptor {
        val propertyDescriptor = PropertyDescriptorImpl.create(
            parent,
            Annotations.EMPTY,
            when {
                propertyModel.getter || propertyModel.setter -> Modality.OPEN
                parent.kind == ClassKind.INTERFACE -> Modality.ABSTRACT
                parent.kind == ClassKind.OBJECT -> Modality.FINAL
                propertyModel.open -> Modality.OPEN
                else -> Modality.FINAL
            },
            Visibilities.PUBLIC,
            !propertyModel.immutable,
            Name.identifier(translateName(propertyModel.name)),
            CallableMemberDescriptor.Kind.DECLARATION,
            SourceElement.NO_SOURCE,
            false,
            false,
            false,
            false,
            false,
            false
        )
        val typeParameters = translateTypeParameters(propertyModel.typeParameters, propertyDescriptor)
        propertyDescriptor.setType(
            translateType(propertyModel.type),
            typeParameters,
            null,
            null
        )
        typeParameters.forEach {
            context.removeTypeParameter(IdentifierEntity(it.name.identifier))
        }
        val getter = if (parent.kind == ClassKind.INTERFACE && propertyModel.getter) {
            PropertyGetterDescriptorImpl(
                propertyDescriptor,
                Annotations.EMPTY,
                propertyDescriptor.modality,
                propertyDescriptor.visibility,
                false,
                false,
                false,
                CallableMemberDescriptor.Kind.DECLARATION,
                null,
                propertyDescriptor.source
            )
        } else {
            DescriptorFactory.createDefaultGetter(
                propertyDescriptor,
                Annotations.EMPTY
            )
        }
        getter.initialize(propertyDescriptor.type)
        val setter = if (parent.kind == ClassKind.INTERFACE && propertyModel.setter) {
            PropertySetterDescriptorImpl(
                propertyDescriptor,
                Annotations.EMPTY,
                propertyDescriptor.modality,
                propertyDescriptor.visibility,
                false,
                false,
                false,
                CallableMemberDescriptor.Kind.DECLARATION,
                null,
                propertyDescriptor.source
            ).also {
                it.initialize(
                    translateParameters(
                        listOf(
                            ParameterModel(
                                name = "value",
                                type = propertyModel.type,
                                initializer = null,
                                vararg = false,
                                optional = false
                            )
                        ), parent = it
                    ).first()
                )
            }

        } else if (!propertyModel.immutable) {
            DescriptorFactory.createDefaultSetter(
                propertyDescriptor,
                Annotations.EMPTY,
                Annotations.EMPTY
            )
        } else {
            null
        }
        propertyDescriptor.initialize(
            getter,
            setter
        )
        return propertyDescriptor
    }

    private fun translateVariable(variableModel: VariableModel, parent: PackageFragmentDescriptor): PropertyDescriptor {
        val variableDescriptor = PropertyDescriptorImpl.create(
            parent,
            translateAnnotations(variableModel.annotations),
            Modality.FINAL,
            Visibilities.PUBLIC,
            !variableModel.immutable,
            Name.identifier(translateName(variableModel.name)),
            CallableMemberDescriptor.Kind.DECLARATION,
            SourceElement.NO_SOURCE,
            false,
            false,
            false,
            false,
            true,
            false
        )
        val typeParameters = translateTypeParameters(variableModel.typeParameters, variableDescriptor)
        variableDescriptor.setType(
            translateType(variableModel.type),
            typeParameters,
            null,
            null
        )
        val getter = DescriptorFactory.createDefaultGetter(
            variableDescriptor,
            Annotations.EMPTY
        )
        getter.initialize(variableDescriptor.type)
        val setter = if (!variableModel.immutable) {
            DescriptorFactory.createDefaultSetter(
                variableDescriptor,
                Annotations.EMPTY,
                Annotations.EMPTY
            )
        } else {
            null
        }
        variableDescriptor.initialize(
            getter,
            setter
        )
        typeParameters.forEach {
            context.removeTypeParameter(IdentifierEntity(it.name.identifier))
        }
        return variableDescriptor
    }

    private fun translateClassLike(
        classLikeModel: ClassLikeModel,
        parent: PackageFragmentDescriptor,
        isCompanion: Boolean = false
    ): ClassDescriptor {
        val companionDescriptor = classLikeModel.companionObject?.let {
            translateObject(it, parent, isCompanion = true)
        }

        var typeParameters: List<TypeParameterDescriptor> = listOf()
        if (classLikeModel is ClassModel) {
            typeParameters = translateTypeParameters(classLikeModel.typeParameters, parent)
        }
        if (classLikeModel is InterfaceModel) {
            typeParameters = translateTypeParameters(classLikeModel.typeParameters, parent)
        }
        val classDescriptor = CustomClassDescriptor(
            parent = parent,
            name = classLikeModel.name,
            modality = when (classLikeModel) {
                is ClassModel -> if (classLikeModel.abstract) Modality.ABSTRACT else Modality.OPEN
                is InterfaceModel -> Modality.ABSTRACT
                else -> Modality.FINAL
            },
            classKind = when (classLikeModel) {
                is ClassModel -> ClassKind.CLASS
                is InterfaceModel -> ClassKind.INTERFACE
                else -> ClassKind.CLASS
            },
            parentTypes = classLikeModel.parentEntities.mapNotNull {
                translateHeritage(it)
            },
            isCompanion = isCompanion,
            companionObject = companionDescriptor,
            typeParameters = typeParameters,
            annotations = translateAnnotations(classLikeModel.annotations)
        )
        context.registerDescriptor(classLikeModel.name, classDescriptor)

        var primaryConstructorDescriptor: ClassConstructorDescriptor? = null
        var constructorDescriptors: Set<ClassConstructorDescriptor> = setOf()
        if (classLikeModel is ClassModel) {
            val constructorModels = classLikeModel.members.filterIsInstance<ConstructorModel>()
            primaryConstructorDescriptor =
                if (classLikeModel.primaryConstructor == null && constructorModels.isEmpty()) {
                    translateConstructor(
                        ConstructorModel(listOf(), listOf()),
                        classDescriptor,
                        true,
                        Visibilities.PUBLIC
                    )
                } else {
                    classLikeModel.primaryConstructor?.let {
                        translateConstructor(
                            it,
                            classDescriptor,
                            true,
                            Visibilities.PUBLIC
                        )
                    }
                }
            constructorDescriptors = (constructorModels.map {
                translateConstructor(
                    it,
                    classDescriptor,
                    false,
                    Visibilities.PUBLIC
                )
            } + primaryConstructorDescriptor).filterNotNull().toSet()
        }

        classDescriptor.initialize(
            SimpleMemberScope((classLikeModel.members.mapNotNull
            {
                when (it) {
                    is MethodModel -> translateMethod(
                        it,
                        classDescriptor
                    )
                    is PropertyModel -> translateProperty(
                        it,
                        classDescriptor
                    )
                    else -> null
                }
            } + companionDescriptor as DeclarationDescriptor?).filterNotNull()),
            constructorDescriptors,
            primaryConstructorDescriptor
        )

        typeParameters.forEach {
            context.removeTypeParameter(IdentifierEntity(it.name.identifier))
        }

        return classDescriptor
    }

    private fun translateObject(
        objectModel: ObjectModel,
        parent: PackageFragmentDescriptor,
        isCompanion: Boolean
    ): ClassDescriptor {
        val objectDescriptor = CustomClassDescriptor(
            parent = parent,
            name = objectModel.name,
            modality = Modality.FINAL,
            classKind = ClassKind.OBJECT,
            parentTypes = objectModel.parentEntities.mapNotNull {
                translateHeritage(it)
            },
            isCompanion = isCompanion,
            companionObject = null,
            typeParameters = listOf(),
            annotations = Annotations.EMPTY
        )
        context.registerDescriptor(objectModel.name, objectDescriptor)
        val privatePrimaryConstructor = translateConstructor(
            ConstructorModel(listOf(), listOf()),
            objectDescriptor,
            true,
            Visibilities.PRIVATE
        )

        objectDescriptor.initialize(
            SimpleMemberScope(objectModel.members.mapNotNull {
                when (it) {
                    is MethodModel -> translateMethod(
                        it,
                        objectDescriptor
                    )
                    is PropertyModel -> translateProperty(
                        it,
                        objectDescriptor
                    )
                    else -> null
                }
            }),
            setOf(privatePrimaryConstructor),
            privatePrimaryConstructor
        )
        (privatePrimaryConstructor as ClassConstructorDescriptorImpl).returnType = objectDescriptor.defaultType
        return objectDescriptor
    }

//    private fun translateEnum(enumModel: EnumModel, parent: PackageFragmentDescriptor) {
//
//    }


    private fun translateTopLevelModel(
        topLevelModel: TopLevelModel,
        parent: PackageFragmentDescriptorImpl
    ): DeclarationDescriptor? {
        return when (topLevelModel) {
            is InterfaceModel -> translateClassLike(topLevelModel, parent)
            is ClassModel -> translateClassLike(topLevelModel, parent)
            is ObjectModel -> translateObject(topLevelModel, parent, isCompanion = false)
            is FunctionModel -> translateFunction(topLevelModel, parent)
            is VariableModel -> translateVariable(topLevelModel, parent)
            is EnumModel -> null
            else -> raiseConcern("untranslated top level declaration: $topLevelModel") {
                null
            }
        }
    }

    fun translateModule(moduleModel: ModuleModel): ModuleDescriptor {
        context.registeredImports.addAll(moduleModel.imports.map { translateName(it.shiftRight()!!) })

        val moduleDescriptor = ModuleDescriptorImpl(
            Name.special("<main>"),
            LockBasedStorageManager.NO_LOCKS,
            DefaultBuiltIns.Instance
        )

        val fragmentDescriptor = object : PackageFragmentDescriptorImpl(
            moduleDescriptor, FqName("")
        ) {
            override fun getMemberScope() =
                SimpleMemberScope(moduleModel.declarations.mapNotNull { translateTopLevelModel(it, this) })
        }

        val provider = PackageFragmentProviderImpl(listOf(fragmentDescriptor))
        moduleDescriptor.setDependencies(
            ModuleDependenciesImpl(
                listOf(moduleDescriptor) + builtIns.builtInsModule,
                setOf(),
                listOf()
            )
        )
        moduleDescriptor.initialize(provider)

        return moduleDescriptor
    }
}

fun SourceSetModel.translateToDescriptors(): ModuleDescriptor {
    return DescriptorTranslator(DescriptorContext(generateJSConfig())).translateModule(sources.first().root)
}