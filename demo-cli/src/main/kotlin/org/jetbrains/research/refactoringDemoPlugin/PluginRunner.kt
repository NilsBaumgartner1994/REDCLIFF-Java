package org.jetbrains.research.refactoringDemoPlugin

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.types.file
import com.google.gson.GsonBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ApplicationStarter
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import org.jetbrains.research.pluginUtilities.openRepository.getKotlinJavaRepositoryOpener
import org.jetbrains.research.refactoringDemoPlugin.parsedAstTypes.*
import org.jetbrains.research.refactoringDemoPlugin.util.extractElementsOfType
import org.jetbrains.research.refactoringDemoPlugin.util.extractModules
import org.jetbrains.research.refactoringDemoPlugin.util.findPsiFilesByExtension
import java.io.File
import kotlin.system.exitProcess


object PluginRunner : ApplicationStarter {
    @Deprecated("Specify it as `id` for extension definition in a plugin descriptor")
    override val commandName: String
        get() = "DemoPluginCLI"

    override val requiredModality: Int
        get() = ApplicationStarter.NOT_IN_EDT

    override fun main(args: List<String>) {
        JavaKotlinDocExtractor().main(args.drop(1))
    }
}

class JavaKotlinDocExtractor : CliktCommand() {
    private val input by argument(help = "Path to the project").file(mustExist = true, canBeFile = false)
    private val output by argument(help = "Output directory").file(canBeFile = true)

    // Thread safe Map
    private val visitedClasses = mutableMapOf<String, Boolean>()

    /**
     * Walks through files in the project, extracts all methods in each Java and Korlin file
     * and saves the method name and the corresponding JavaDoc to the output file.
     */
    override fun run() {
        println("Starting")
        deleteAllFilesRecursiveInFolder(output)

        // Delete caches
        val projectManager = ProjectManager.getInstance()
        val project = projectManager.loadAndOpenProject(input.toPath().toString())
        try{
            if (project != null) {
                //projectManager.closeAndDispose(project)
            }
        } catch (e: Exception){
            println("Error while closing project")
            println(e)
        }

        val repositoryOpener = getKotlinJavaRepositoryOpener()
        repositoryOpener.openProjectWithResolve(input.toPath()) { project ->
            ApplicationManager.getApplication().invokeAndWait {
                val modules = project.extractModules()
                parseAllModulesSourceCodeToAstClasses(modules)
                parseAllModulesAuxClassesToAstClasses(modules)
            }
            true
        }
        println("Done")

        exitProcess(0)
    }

    private fun parseAllModulesAuxClassesToAstClasses(modules: List<Module>){
        parseAllModulesToAstClasses(modules, false)
    }

    private fun parseAllModulesSourceCodeToAstClasses(modules: List<Module>){
        parseAllModulesToAstClasses(modules, true)
    }

    private fun parseAllModulesToAstClasses(modules: List<Module>, onlySourceCode: Boolean){
        for (module in modules) {
            println("Processing module: " + module.name)
            try {
                parseModuleToAstClasses(module, onlySourceCode)
            } catch (e: Exception) {
                println("Error while processing module: " + module.name)
                println(e)
            }
        }
    }


    private fun deleteAllFilesRecursiveInFolder(folder: File){
        println("Deleting all files in folder: "+folder.path)
        deleteAllFilesInDirectory(folder)
    }

    private fun deleteAllFilesInDirectory(folder: File){
        if(folder.isDirectory){
            for(file in folder.listFiles()){
                deleteAllFilesInDirectory(file)
            }
        }
        folder.delete()
    }

    private fun hasTypeVariable(psiClass: PsiClass): Boolean{
        for (typeParameter in psiClass.typeParameters) {
            if (typeParameter is PsiTypeParameter) { // TODO: always true?
                return true
            }
        }
        return false;
    }

    private fun hasTypeVariable(type: PsiType): Boolean {
        var hasTypeVar = false

        type.accept(object : PsiTypeVisitor<Unit>() {
            override fun visitType(type: PsiType) {
                if (type is PsiClassType) {
                    val resolve = type.resolve()
                    if (resolve is PsiTypeParameter) {
                        hasTypeVar = true
                    }
                    for (typeArg in type.parameters) {
                        typeArg.accept(this)
                    }
                }
            }
        })

        return hasTypeVar
    }

    private fun log(psiClass: PsiClass, message: String){
        val debug = psiClass.name.equals("Caffeine")
        if(debug){
            println("LOG: "+psiClass.name+": "+message)
        }
    }

    private fun parseModuleToAstClasses(module: Module, onlySourceCode: Boolean){
        val javaClasses = module.getPsiClasses("java")

        for(psiClass in javaClasses) {
            log(psiClass,"Processing (in module: "+module.name+") class: "+psiClass.name)
            // check if psiClass is a genric like E from class MyList<E> then skip it

            if(onlySourceCode){ // Step 1. Parse all source code classes
                val isSourceCode = true;
                processClass(psiClass, module, isSourceCode)
            } else { // Step 2. Parse all aux classes, since we have all source code classes already parsed
                processSupersForClassAndInterfaces(psiClass, module)
            }
        }
        println("Done with module: "+module.name)
    }

    private fun processSupersForClassAndInterfaces(psiClass: PsiClass, module: Module){
        //println("Processing Aux class: "+psiClass.name+" in module: "+module.name)
        // get all super classes and interfaces, since we have all source files already parsed
        val supers = psiClass.supers
        for(superClass in supers){
            if(superClass is PsiClass){
                processClass(superClass, module, false)
                // also process their supers
                processSupersForClassAndInterfaces(superClass, module)
            }
        }

        // get all super interfaces
        val superInterfaces = psiClass.interfaces
        for(superInterface in superInterfaces){
            if(superInterface is PsiClass){
                processClass(superInterface, module, false)
                // also process their supers
                processSupersForClassAndInterfaces(superInterface, module)
            }
        }
    }

    private fun processClass(psiClass: PsiClass, module: Module, isSourceCode: Boolean){
        val debug = psiClass.name.equals("Caffeine")

        log(psiClass,"START Processing class: "+psiClass.name+" in module: "+module.name)

        val classContextKey = getClassOrInterfaceKey(psiClass)

        if(classContextKey.equals("java.lang.Object")){
            log(psiClass,"-- Skipping class: "+psiClass.name+" because it is Object")
            return;
        }

        if(psiClass is PsiTypeParameter){
            log(psiClass,"-- Skipping class: "+psiClass.name+" because it is a generic")
            return;
        }

        log(psiClass,"-- Check if class is already visited: " + psiClass.name + " in module: " + module.name)

        //val classContextKey = getClassOrInterfaceKey(psiClass)
        val fileName = module.name+"/"+classContextKey + ".json"

        // check if class is already visited
        if(visitedClasses.containsKey(fileName)){
            log(psiClass,"-- Skipping class: " + psiClass.name + " because it is already visited")
            return;
        } else {
            visitedClasses[fileName] = true
            log(psiClass,"-- class not visited yet: " + psiClass.name)
        }

        val classContext = visitClassOrInterface(psiClass, isSourceCode)
        saveClassContextToFile(classContext, psiClass, fileName)
    }

    private fun saveClassContextToFile(classContext: ClassOrInterfaceTypeContext, psiClass: PsiClass, fileName: String){
        log(psiClass,"-- Getting gson instance in class: "+psiClass.name)

        log(psiClass,"-- Writing to file: "+fileName)
        log(psiClass,"-- Class file path: "+psiClass.containingFile.virtualFile.path);
        val outputFile = File("$output/"+fileName);
        outputFile.parentFile.mkdirs() // create parent directories if they do not exist
        val fileContent = objectToString(classContext)
        outputFile.writeText(fileContent, Charsets.UTF_8)
    }

    private fun objectToString(classContext: ClassOrInterfaceTypeContext): String{
        val gson = GsonBuilder().setPrettyPrinting().create()
        var asJsonString = gson.toJson(classContext)
        var fileContent = asJsonString;

        fileContent = fileContent.replace("\\u003c", "<")
        fileContent = fileContent.replace("\\u003e", ">")


        return fileContent;
    }

    private fun visitClassOrInterface(psiClass: PsiClass, isSourceCode: Boolean): ClassOrInterfaceTypeContext{
        log(psiClass,"-- visitClassOrInterface: "+psiClass.name)

        log(psiClass,"-- Creating classContext")
        val classContext = ClassOrInterfaceTypeContext()
        log(psiClass,"-- Created classContext")

        log(psiClass,"-- Extracting class informations")
        extractClassInformations(psiClass,classContext, isSourceCode)

        classContext.file_path = psiClass.containingFile.virtualFile.path

        log(psiClass,"-- Extracting fields")
        extractFields(psiClass,classContext)

        log(psiClass,"-- Extracting methods")
        extractMethods(psiClass,classContext)

        log(psiClass,"-- Extracting extends and implements")
        extractExtendsAndImplements(psiClass,classContext)

        /**
        // get all classes and interfaces that are defined inside this class
        val innerClasses = psiClass.innerClasses
        for(innerClass in innerClasses){
            val innerClassContext = visitClassOrInterface(innerClass)
            classContext.innerDefinedClasses[innerClassContext.key] = innerClassContext
        }
        val innerInterfaces = psiClass.innerClasses
        for(innerInterface in innerInterfaces){
            val innerInterfaceContext = visitClassOrInterface(innerInterface)
            classContext.innerDefinedInterfaces[innerInterfaceContext.key] = innerInterfaceContext
        }
        */

        // if this class is an inner class, get the outer class or interface
        val outerClass = psiClass.containingClass
        if(outerClass != null){
            val outClassKey = getClassOrInterfaceKey(outerClass)
            classContext.definedInClassOrInterfaceTypeKey = outClassKey
        }

        log(psiClass,"-- Done with visitClassOrInterface: "+psiClass.name)
        return classContext
    }

    private fun extractClassInformations(psiClass: PsiClass,classContext: ClassOrInterfaceTypeContext, isSourceCode: Boolean){
        classContext.name = psiClass.name ?: ""
        classContext.key = getClassOrInterfaceKey(psiClass)
        val isInterface = psiClass.isInterface
        classContext.type = "class"
        if(isInterface) {
            classContext.type = "interface"
        }

        var nameRange = psiClass.textRange
        val nameTextRange = psiClass.nameIdentifier?.textRange
        if(nameTextRange!=null){
            nameRange = nameTextRange
        }

        classContext.hasTypeVariable = hasTypeVariable(psiClass)
        classContext.auxclass = !isSourceCode // if class is not source code, it is an aux class

        classContext.position = getAstPosition("extractClass",nameRange,psiClass.project,psiClass.containingFile)
        classContext.anonymous = psiClass.name == null

        // Extract the modifiers
        classContext.modifiers = getModifiers(psiClass.modifierList)
    }

    private fun extractExtendsAndImplements(psiClass: PsiClass, classContext: ClassOrInterfaceTypeContext){
        // Extract the interfaces this class implements
        val implementsLists = psiClass.implementsList
        if (implementsLists != null) {
            val superinterfaces = implementsLists.referenceElements
            for (superinterface in superinterfaces) {
                val psiSuperInterface: PsiClass = superinterface.resolve() as PsiClass
                val fullQualifiedName: String = getClassOrInterfaceKey(psiSuperInterface)
                if (fullQualifiedName != null) {
                    classContext.implements_.add(fullQualifiedName)
                }
            }
        }

        // Extract the classes this class extends
        val extendsLists = psiClass.extendsList
        if (extendsLists != null) {
            val superclasses = extendsLists.referenceElements
            for (superclass in superclasses) {
                val psiSuperClass: PsiClass = superclass.resolve() as PsiClass
                val fullQualifiedName: String = getClassOrInterfaceKey(psiSuperClass)
                if (fullQualifiedName != null) {
                    classContext.extends_.add(fullQualifiedName)
                }
            }
        }
    }

    private fun getClassOrInterfaceKey(psiClass: PsiClass): String{
        val packageName = psiClass.qualifiedName?.split(".")?.dropLast(1)?.joinToString(".")
        return if(packageName != null) "$packageName.${psiClass.name}" else psiClass.name ?: ""
    }

    private fun getAstPosition(logMessage: String, textRangeOptional: TextRange?, project: Project, file: PsiFile): AstPosition {
        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val position = AstPosition()
        try{
            if (document != null && textRangeOptional != null) {
                val textRange: TextRange = textRangeOptional
                val startOffset = textRange.startOffset
                val endOffset = textRange.endOffset
                position.startLine = document.getLineNumber(startOffset) + 1
                position.endLine = document.getLineNumber(endOffset) + 1
                position.endColumn = endOffset - document.getLineStartOffset(position.endLine - 1) + 1
                position.startColumn = startOffset - document.getLineStartOffset(position.startLine - 1) + 1
            } else {
                // Handle the case where the document is null, maybe log an error or throw an exception
            }
        } catch (e: Exception){
            println("Error while getting position for: "+logMessage)
            println(e)
        }
        return position
    }

    private fun extractFields(psiClass: PsiClass,classContext: ClassOrInterfaceTypeContext){
        val classKey = getClassOrInterfaceKey(psiClass)
        val memberFieldKeyPre = classKey + "/memberField/"

        log(psiClass,"-- Extracting fields for class: "+classKey)
        val fields = psiClass.fields
        for(field in fields){

            log(psiClass,"---- field: "+field.name)
            log(psiClass,"------ field.text: "+field.text)
            log(psiClass,"------ field.textRange: "+field.textRange)
            log(psiClass,"------ field.type: "+field.type)
            log(psiClass,"------ field.type.canonicalText: "+field.type.canonicalText)

            val fieldContext = MemberFieldParameterTypeContext()

            // Set the properties of the fieldContext based on the field
            val fieldName: String = field.name
            fieldContext.name = fieldName

            fieldContext.type = field.type.canonicalText;


            //TODO check if this is correct
            fieldContext.hasTypeVariable = hasVariableTypeVariable(field)

            // Set the position
            fieldContext.position = getAstPosition("extractField",field.nameIdentifier.textRange,psiClass.project,psiClass.containingFile)

            fieldContext.classOrInterfaceKey = classKey;

            // Extract the modifiers
            fieldContext.modifiers = getModifiers(field.modifierList)

            fieldContext.key = memberFieldKeyPre + fieldName

            // Add the fieldContext to the classContext.fields
            classContext.fields[fieldContext.key!!] = fieldContext
        }
    }

    private fun hasVariableTypeVariable(variable: PsiVariable): Boolean {
        val type = variable.type
        return hasTypeVariable(type)
    }

    private fun extractMethods(psiClass: PsiClass, classContext: ClassOrInterfaceTypeContext){
        val classOrInterfaceKey = getClassOrInterfaceKey(psiClass)

        // If you want to only get the fields of the top-level class and not any inner classes, you would need to add a check to exclude fields that belong to inner classes. One way to do this could be to check the parent of each field and see if it's the top-level class node.

        // If you want to only get the fields of the top-level class and not any inner classes, you would need to add a check to exclude fields that belong to inner classes. One way to do this could be to check the parent of each field and see if it's the top-level class node.
        val methods = psiClass.methods
        for (method in methods) {
            if(method.isConstructor){ // skip constructors
                continue
            }

            val methodContext = MethodTypeContext()
            // Set the properties of the methodContext based on the method
            methodContext.name = method.name
            methodContext.type = method.returnType?.canonicalText ?: null

            //System.out.println("----------------");
            //System.out.println("methodContext.name: "+methodContext.name);

            var nameRange = method.textRange
            val nameTextRange = method.nameIdentifier?.textRange
            if(nameTextRange!=null){
                nameRange = nameTextRange
            }
            // Set the position
            methodContext.position = getAstPosition("extractMethod",nameRange,psiClass.project,psiClass.containingFile)
            methodContext.classOrInterfaceKey = classOrInterfaceKey

            // Extract the modifiers and check for @Override annotation
            methodContext.modifiers = getModifiers(method.modifierList)


            methodContext.overrideAnnotation = method.hasAnnotation("Override") // quick way to check if method is overridden
            // if method is overridden set overrideAnnotation to true
            if(!methodContext.overrideAnnotation){
                val superMethods = method.findSuperMethods()
                if(superMethods.isNotEmpty()){
                    methodContext.overrideAnnotation = true
                }
            }



            // Extract the parameters
            val parameters = method.parameterList.parameters
            for (parameter in parameters) {
                val parameterContext = MethodParameterTypeContext()
                parameterContext.name = parameter.name

                parameterContext.type = parameter.type.canonicalText
                parameterContext.hasTypeVariable = hasVariableTypeVariable(parameter)


                // Set the position
                var paramRange = parameter.textRange
                val paramTextRange = parameter.nameIdentifier?.textRange
                if(paramTextRange!=null){
                    paramRange = paramTextRange
                }
                parameterContext.position = getAstPosition("extractParameter",paramRange,psiClass.project,psiClass.containingFile)

                // Extract the modifiers
                parameterContext.modifiers = getModifiers(parameter.modifierList)

                //parameterContext.methodKey = methodContext.key;
                // We cant set the methodKey directly, since the method key is not yet defined

                // Add the parameterContext to the methodContext.parameters
                methodContext.parameters.add(parameterContext)
            }

            // set method key
            // Java method key is the method signature. The signature is: method name + parameters (type and order)
            var methodContextParametersKey = classOrInterfaceKey + "/method/" + method.name + "("
            val amountParameters: Int = methodContext.parameters.size
            for (i in 0 until amountParameters) {
                val parameterContext: MethodParameterTypeContext = methodContext.parameters.get(i)
                val parameterTypeAndName = parameterContext.type + " " + parameterContext.name
                methodContextParametersKey += parameterTypeAndName
                if (i + 1 < amountParameters) {
                    methodContextParametersKey += ", "
                }
            }
            methodContextParametersKey += ")"
            for (i in 0 until amountParameters) {
                val parameterContext: MethodParameterTypeContext = methodContext.parameters.get(i)
                parameterContext.key = methodContextParametersKey + "/parameter/" + parameterContext.name
            }
            methodContext.key = methodContextParametersKey
            for (parameterContext in methodContext.parameters) {
                parameterContext.methodKey = methodContext.key
            }


            // Add the methodContext to the classContext.methods
            classContext.methods[methodContext.key!!] = methodContext
        }
    }

    private fun getModifiers(modifierList: PsiModifierList?): ArrayList<String> {
        val returnList = ArrayList<String>()
        if (modifierList == null) {
            return returnList
        }

        // Split the text of the PsiModifierList into words
        val words = modifierList.text.split("\\s+".toRegex()) // this way we keep the order of the modifiers

        // Check each word to see if it's a valid Java modifier
        for (word in words) {
            if (PsiModifier.MODIFIERS.contains(word)) {
                returnList.add(word)
            }
        }

        return returnList
    }

    private fun Module.getPsiClasses(extension: String): List<PsiClass> {
        val psiFiles = this.findPsiFilesByExtension(extension)
        return psiFiles.flatMap { it.extractElementsOfType(PsiClass::class.java) }
    }
}
