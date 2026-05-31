package com.dark.gguf_lib.toolcalling

import com.dark.gguf_lib.toolcalling.ToolDefinitionBuilder.ToolDefinition
import org.json.JSONObject
import java.lang.reflect.Method
import java.lang.reflect.Parameter

/**
 * Annotation to mark a Kotlin method as a Tool for LLM function calling.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class Tool(val name: String, val description: String)

/**
 * Annotation to describe a value parameter in an LLM Tool.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ToolParam(val description: String, val required: Boolean = true)

/**
 * AnnotatedToolExecutor - Uses reflection to parse classes containing `@Tool` annotations
 * and dynamically execute them upon model tool-calling events.
 */
class AnnotatedToolExecutor {

    private val toolRegistry = mutableMapOf<String, RegisteredTool>()

    data class RegisteredTool(
        val name: String,
        val description: String,
        val instance: Any,
        val method: Method,
        val definition: ToolDefinition
    )

    /**
     * Scan any registerable class instance, register annotated methods,
     * and return their generated ToolDefinitions.
     */
    fun registerTools(handler: Any): List<ToolDefinition> {
        val definitions = mutableListOf<ToolDefinition>()
        val clazz = handler.javaClass
        
        for (method in clazz.declaredMethods) {
            val toolAnnotation = method.getAnnotation(Tool::class.java) ?: continue
            
            val toolName = toolAnnotation.name.ifEmpty { method.name }
            val toolDesc = toolAnnotation.description
            
            val builder = ToolDefinitionBuilder(toolName, toolDesc)
            
            for (param in method.parameters) {
                val paramAnnotation = param.getAnnotation(ToolParam::class.java)
                val paramDesc = paramAnnotation?.description ?: "Parameter ${param.name}"
                val paramRequired = paramAnnotation?.required ?: true
                
                when (param.type) {
                    String::class.java -> builder.stringParam(param.name, paramDesc, paramRequired)
                    Int::class.java, java.lang.Integer::class.java -> builder.integerParam(param.name, paramDesc, paramRequired)
                    Double::class.java, java.lang.Double::class.java, Float::class.java, java.lang.Float::class.java -> builder.numberParam(param.name, paramDesc, paramRequired)
                    Boolean::class.java, java.lang.Boolean::class.java -> builder.booleanParam(param.name, paramDesc, paramRequired)
                    else -> builder.stringParam(param.name, paramDesc, paramRequired) // Fallback to string
                }
            }
            
            val definition = builder.build()
            val registeredTool = RegisteredTool(toolName, toolDesc, handler, method, definition)
            toolRegistry[toolName] = registeredTool
            definitions.add(definition)
        }
        return definitions
    }

    /**
     * Dynamically execute a tool call event and return the execution result.
     */
    fun execute(toolName: String, argsJson: String): String {
        val tool = toolRegistry[toolName] ?: return "Error: Tool '$toolName' is not registered."
        
        return try {
            val argsObj = JSONObject(argsJson)
            val method = tool.method
            val params = method.parameters
            val argValues = Array(params.size) { i ->
                val param = params[i]
                val name = param.name
                
                if (!argsObj.has(name)) {
                    if (param.getAnnotation(ToolParam::class.java)?.required == true) {
                        throw IllegalArgumentException("Missing required parameter: $name")
                    }
                    null
                } else {
                    when (param.type) {
                        String::class.java -> argsObj.getString(name)
                        Int::class.java, java.lang.Integer::class.java -> argsObj.getInt(name)
                        Double::class.java, java.lang.Double::class.java -> argsObj.getDouble(name)
                        Float::class.java, java.lang.Float::class.java -> argsObj.getDouble(name).toFloat()
                        Boolean::class.java, java.lang.Boolean::class.java -> argsObj.getBoolean(name)
                        else -> argsObj.get(name).toString()
                    }
                }
            }
            
            method.isAccessible = true
            val result = method.invoke(tool.instance, *argValues)
            result?.toString() ?: "Success"
        } catch (e: Exception) {
            "Error executing tool '$toolName': ${e.localizedMessage ?: e.message}"
        }
    }

    fun clear() {
        toolRegistry.clear()
    }
}
