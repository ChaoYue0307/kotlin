/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.idea

import com.google.common.html.HtmlEscapers
import com.intellij.codeInsight.documentation.DocumentationManagerUtil
import com.intellij.codeInsight.javadoc.JavaDocInfoGeneratorFactory
import com.intellij.lang.documentation.AbstractDocumentationProvider
import com.intellij.lang.java.JavaDocumentationProvider
import com.intellij.openapi.diagnostic.Logger
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import org.jetbrains.kotlin.asJava.LightClassUtil
import org.jetbrains.kotlin.asJava.elements.KtLightDeclaration
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.descriptors.ClassifierDescriptor
import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ValueParameterDescriptor
import org.jetbrains.kotlin.idea.caches.resolve.analyze
import org.jetbrains.kotlin.idea.caches.resolve.getResolutionFacade
import org.jetbrains.kotlin.idea.codeInsight.DescriptorToSourceUtilsIde
import org.jetbrains.kotlin.idea.core.completion.DeclarationLookupObject
import org.jetbrains.kotlin.idea.kdoc.KDocRenderer
import org.jetbrains.kotlin.idea.kdoc.findKDoc
import org.jetbrains.kotlin.idea.kdoc.isBoringBuiltinClass
import org.jetbrains.kotlin.idea.kdoc.resolveKDocLink
import org.jetbrains.kotlin.idea.project.languageVersionSettings
import org.jetbrains.kotlin.idea.references.mainReference
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.getElementTextWithContext
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.renderer.ClassifierNamePolicy
import org.jetbrains.kotlin.renderer.DescriptorRenderer
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.deprecatedByAnnotationReplaceWithExpression
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe
import org.jetbrains.kotlin.resolve.getDeprecations
import org.jetbrains.kotlin.resolve.lazy.BodyResolveMode
import org.jetbrains.kotlin.utils.addToStdlib.constant

class HtmlClassifierNamePolicy(val base: ClassifierNamePolicy) : ClassifierNamePolicy {
    override fun renderClassifier(classifier: ClassifierDescriptor, renderer: DescriptorRenderer): String {
        val name = base.renderClassifier(classifier, renderer)
        if (classifier.isBoringBuiltinClass())
            return name
        return buildString {
            val ref = classifier.fqNameUnsafe.toString()
            DocumentationManagerUtil.createHyperlink(this, ref, name, true)
        }
    }
}

class KotlinQuickDocumentationProvider : AbstractDocumentationProvider() {

    override fun getQuickNavigateInfo(element: PsiElement?, originalElement: PsiElement?): String? {
        return if (element == null) null else getText(element, originalElement, true)
    }

    override fun generateDoc(element: PsiElement, originalElement: PsiElement?): String? {
        return getText(element, originalElement, false)
    }

    override fun getDocumentationElementForLink(psiManager: PsiManager, link: String, context: PsiElement?): PsiElement? {
        val navElement = context?.navigationElement as? KtElement ?: return null
        val bindingContext = navElement.analyze(BodyResolveMode.PARTIAL)
        val contextDescriptor = bindingContext[BindingContext.DECLARATION_TO_DESCRIPTOR, navElement] ?: return null
        val descriptors = resolveKDocLink(bindingContext, navElement.getResolutionFacade(),
                                          contextDescriptor, null, link.split('.'))
        val target = descriptors.firstOrNull() ?: return null
        return DescriptorToSourceUtilsIde.getAnyDeclaration(psiManager.project, target)
    }

    override fun getDocumentationElementForLookupItem(psiManager: PsiManager, `object`: Any?, element: PsiElement?): PsiElement? {
        if (`object` is DeclarationLookupObject) {
            return `object`.psiElement
        }
        return null
    }

    companion object {
        private val LOG = Logger.getInstance(KotlinQuickDocumentationProvider::class.java)

        private val DESCRIPTOR_RENDERER = DescriptorRenderer.HTML.withOptions {
            classifierNamePolicy = HtmlClassifierNamePolicy(ClassifierNamePolicy.SHORT)
            renderCompanionObjectName = true
        }

        private fun getText(element: PsiElement, originalElement: PsiElement?, quickNavigation: Boolean): String? {
            if (element is KtDeclaration) {
                return renderKotlinDeclaration(element, quickNavigation)
            }
            else if (element is KtNameReferenceExpression && element.getReferencedName() == "it") {
                return renderKotlinImplicitLambdaParameter(element, quickNavigation)
            }
            else if (element is KtLightDeclaration<*, *>) {
                val origin = element.kotlinOrigin ?: return null
                return renderKotlinDeclaration(origin, quickNavigation)
            }

            if (quickNavigation) {
                val referenceExpression = originalElement?.getNonStrictParentOfType<KtReferenceExpression>()
                if (referenceExpression != null) {
                    val context = referenceExpression.analyze(BodyResolveMode.PARTIAL)
                    val declarationDescriptor = context[BindingContext.REFERENCE_TARGET, referenceExpression]
                    if (declarationDescriptor != null) {
                        return mixKotlinToJava(declarationDescriptor, element, originalElement)
                    }
                }
            }
            else {
                // This element was resolved to non-kotlin element, it will be rendered with own provider
            }

            return null
        }

        private fun renderKotlinDeclaration(declaration: KtExpression, quickNavigation: Boolean): String {
            val context = declaration.analyze(BodyResolveMode.PARTIAL)
            val declarationDescriptor = context[BindingContext.DECLARATION_TO_DESCRIPTOR, declaration]

            if (declarationDescriptor == null) {
                LOG.info("Failed to find descriptor for declaration " + declaration.getElementTextWithContext())
                return "No documentation available"
            }

            return renderKotlin(context, declarationDescriptor, quickNavigation, declaration.languageVersionSettings)
        }

        private fun renderKotlinImplicitLambdaParameter(element: KtReferenceExpression, quickNavigation: Boolean): String? {
            val context = element.analyze(BodyResolveMode.PARTIAL)
            val target = element.mainReference.resolveToDescriptors(context).singleOrNull() as? ValueParameterDescriptor? ?: return null
            return renderKotlin(context, target, quickNavigation, element.languageVersionSettings)
        }

        private fun renderKotlin(
                context: BindingContext,
                declarationDescriptor: DeclarationDescriptor,
                quickNavigation: Boolean,
                languageVersionSettings: LanguageVersionSettings
        ): String {
            @Suppress("NAME_SHADOWING")
            var declarationDescriptor = declarationDescriptor
            if (declarationDescriptor is ValueParameterDescriptor) {
                val property = context[BindingContext.VALUE_PARAMETER_AS_PROPERTY, declarationDescriptor]
                if (property != null) {
                    declarationDescriptor = property
                }
            }

            var renderedDecl = DESCRIPTOR_RENDERER.render(declarationDescriptor)

            if (!quickNavigation) {
                renderedDecl = "<pre>$renderedDecl</pre>"
            }

            renderedDecl += renderDeprecationInfo(declarationDescriptor, languageVersionSettings)

            if (!quickNavigation) {
                val comment = declarationDescriptor.findKDoc()
                if (comment != null) {
                    val renderedComment = KDocRenderer.renderKDoc(comment)
                    if (renderedComment.startsWith("<p>")) {
                        renderedDecl += renderedComment
                    }
                    else {
                        renderedDecl = "$renderedDecl<br/>$renderedComment"
                    }
                }
                else {
                    if (declarationDescriptor is CallableDescriptor) { // If we couldn't find KDoc, try to find javadoc in one of super's
                        val psi = declarationDescriptor.findPsi() as? KtFunction
                        if (psi != null) {
                            val lightElement = LightClassUtil.getLightClassMethod(psi) // Light method for super's scan in javadoc info gen
                            val javaDocInfoGenerator = JavaDocInfoGeneratorFactory.create(psi.project, lightElement)
                            val builder = StringBuilder()
                            if (javaDocInfoGenerator.generateDocInfoCore(builder, false))
                                renderedDecl += builder.toString().substringAfter("</PRE>") // Cut off light method signature
                        }
                    }
                }
            }

            return renderedDecl
        }

        private fun renderDeprecationInfo(
                declarationDescriptor: DeclarationDescriptor,
                languageVersionSettings: LanguageVersionSettings
        ): String {
            val deprecation = declarationDescriptor.getDeprecations(languageVersionSettings).firstOrNull() ?: return ""

            return buildString {
                wrapTag("DL") {
                    deprecation.message?.let { message ->
                        wrapTag("DT") { wrapTag("b") { append("Deprecated:") } }
                        wrapTag("DD") {
                            append(message.htmlEscape())
                        }
                    }
                    deprecation.deprecatedByAnnotationReplaceWithExpression()?.let { replaceWith ->
                        wrapTag("DT") { wrapTag("b") { append("Replace with:") } }
                        wrapTag("DD") {
                            wrapTag("code") { append(replaceWith.htmlEscape()) }
                        }
                    }
                }
            }
        }

        fun String.htmlEscape(): String = HtmlEscapers.htmlEscaper().escape(this)

        private inline fun StringBuilder.wrap(prefix: String, postfix: String, crossinline body: () -> Unit) {
            this.append(prefix)
            body()
            this.append(postfix)
        }

        private inline fun StringBuilder.wrapTag(tag: String, crossinline body: () -> Unit) {
            wrap("<$tag>", "</$tag>", body)
        }

        private fun mixKotlinToJava(declarationDescriptor: DeclarationDescriptor, element: PsiElement, originalElement: PsiElement?): String? {
            val originalInfo = JavaDocumentationProvider().getQuickNavigateInfo(element, originalElement)
            if (originalInfo != null) {
                val renderedDecl = constant { DESCRIPTOR_RENDERER.withOptions { withDefinedIn = false } }.render(declarationDescriptor)
                return renderedDecl + "<br/>Java declaration:<br/>" + originalInfo
            }

            return null
        }
    }
}
