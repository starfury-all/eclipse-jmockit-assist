/*
 * Copyright (c) 2012 Andrejs Jermakovics.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Andrejs Jermakovics - initial implementation
 */
package jmockit.assist;

import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IAnnotationBinding;
import org.eclipse.jdt.core.dom.IMemberValuePairBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.internal.corext.dom.Bindings;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.ui.SharedASTProvider;

@SuppressWarnings("restriction")
public class ASTUtil
{

	public static void resolveParameterTypes(final String[] paramTypes, final IType declaringType)
	{
		for(int i = 0; i < paramTypes.length; i++)
		{
			try
			{
				String resTypeName = JavaModelUtil.getResolvedTypeName(paramTypes[i], declaringType);
				paramTypes[i] = Util.firstNonNull(resTypeName, paramTypes[i]);
			}
			catch(Exception e)
			{
				Activator.log(e);
			}
		}
	}

	public static ITypeBinding getFirstTypeParameter(final ASTNode node)
	{
		ITypeBinding declaringType = null;

		if( node.getParent() instanceof ClassInstanceCreation ) // for anonymous
		{
			ClassInstanceCreation creation = (ClassInstanceCreation) node.getParent();
			Type ctype = creation.getType();

			declaringType = getFirstTypeParam(ctype);
		}
		else if( node instanceof TypeDeclaration )
		{
			Type ctype = ((TypeDeclaration) node).getSuperclassType();
			declaringType = getFirstTypeParam(ctype);
		}

		return declaringType;
	}

	@SuppressWarnings("unchecked")
	public static ITypeBinding getFirstTypeParam(final Type ctype)
	{
		ITypeBinding declaringType = null;

		if( ctype instanceof ParameterizedType )
		{
			ParameterizedType paramType = (ParameterizedType) ctype;
			List<Type> typeArgs = paramType.typeArguments();

			if( !typeArgs.isEmpty() )
			{
				Type arg1 = typeArgs.get(0);
				declaringType = arg1.resolveBinding();
			}
		}
		return declaringType;
	}

	public static void addAnnotation(final String annotation,
			final IJavaProject project, final ASTRewrite rewrite, final MethodDeclaration decl,
			final IMethodBinding binding)
	{
		String version= project.getOption(JavaCore.COMPILER_COMPLIANCE, true);

		if (!binding.getDeclaringClass().isInterface()
				|| !JavaModelUtil.isVersionLessThan(version, JavaCore.VERSION_1_6))
		{
			final Annotation marker= rewrite.getAST().newMarkerAnnotation();
			marker.setTypeName(rewrite.getAST().newSimpleName(annotation)); //$NON-NLS-1$
			rewrite.getListRewrite(decl, MethodDeclaration.MODIFIERS2_PROPERTY).insertFirst(marker, null);
		}
	}

	public static boolean isAnnotationPresent(final IAnnotationBinding[] annotations, final String annName)
	{
		return findAnnotation(annotations, annName) != null;
	}

	public static boolean isMockMethod(final IMethodBinding meth)
	{
		return meth != null && isAnnotationPresent(meth.getAnnotations(), "mockit.Mock");
	}

	public static boolean isReentrantMockMethod(final IMethodBinding meth)
	{
		IAnnotationBinding ann = findAnnotation(meth.getAnnotations(), "mockit.Mock");

		if( ann != null )
		{
			for(IMemberValuePairBinding pair: ann.getDeclaredMemberValuePairs() )
			{
				if( "reentrant".equals( pair.getName()) )
				{
					return Boolean.valueOf(pair.getValue().toString());
				}
			}
		}

		return false;
	}

	public static boolean hasMockClass(final ITypeBinding type)
	{
		return findAnnotation(type.getAnnotations(), "mockit.MockClass") != null;
	}

	public static IAnnotationBinding findAnnotation(final IAnnotationBinding[] annotations, final String annName)
	{
		for(IAnnotationBinding ann: annotations)
		{
			if( annName.equals( ann.getAnnotationType().getQualifiedName()) )
			{
				return ann;
			}
		}

		return null;
	}

	public static ITypeBinding findRealClassType(final ITypeBinding mockClass)
	{
		IAnnotationBinding ann = findAnnotation(mockClass.getAnnotations(), "mockit.MockClass");

		for(IMemberValuePairBinding pair: ann.getDeclaredMemberValuePairs() )
		{
			if( "realClass".equals( pair.getName()) )
			{
				if( pair.getValue() instanceof ITypeBinding )
				{
					return (ITypeBinding) pair.getValue();
				}
			}
		}

		return null;
	}

	public static boolean isMockUpType(final ITypeBinding declaringClass)
	{
		return declaringClass != null && declaringClass.getQualifiedName().startsWith("mockit.MockUp");
	}

	public static CompilationUnit getAstOrParse(final ITypeRoot iTypeRoot, final IProgressMonitor mon)
	{
		CompilationUnit cu = SharedASTProvider.getAST(iTypeRoot, SharedASTProvider.WAIT_ACTIVE_ONLY, mon);

		if( cu == null )
		{
			cu = parse(iTypeRoot, mon);
		}

		return cu;
	}

	public static CompilationUnit parse(final ITypeRoot unit, final IProgressMonitor mon)
	{
		//System.err.println(" - - - Parsing " + unit.getElementName());

		ASTParser parser = ASTParser.newParser(AST.JLS3);
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(unit);
		parser.setProject(unit.getJavaProject());
		parser.setResolveBindings(true);
		parser.setStatementsRecovery(true);
		return (CompilationUnit) parser.createAST(mon); // parse
	}

	@SuppressWarnings("unchecked")
	public static <T extends ASTNode> T findAncestor(final ASTNode node, final Class<T> clazz)
	{
		ASTNode parent = node.getParent();
		while( parent != null )
		{
			if( parent.getClass() == clazz )
			{
				break;
			}
			parent = parent.getParent();
		}
		return (T) parent;
	}

	public static ITypeBinding findMockedType(final MethodInvocation node)
	{
		MethodDeclaration surroundingMeth = findAncestor(node, MethodDeclaration.class);

		if( surroundingMeth != null )
		{
			return findMockedType(surroundingMeth, surroundingMeth.resolveBinding());
		}
		else
		{
			return null;
		}
	}

	public static ITypeBinding findMockedType(final MethodDeclaration node, final IMethodBinding meth)
	{
		ITypeBinding typePar = null;

		ITypeBinding declaringClass = meth.getDeclaringClass();

		boolean isMockClass = hasMockClass(declaringClass);
		boolean isMockUpType = isMockUpType(declaringClass.getSuperclass());

		if (isMockUpType)
		{
			typePar = getFirstTypeParameter(node.getParent());
		}
		else if ( isMockClass )
		{
			typePar = findRealClassType(declaringClass);
		}
		return typePar;
	}

	public static IMethodBinding findMethodInType(final ITypeBinding type, final String name,
			final ITypeBinding[] paramTypes)
	{
		IMethodBinding origMethod;

		if (type.isInterface())
		{
			origMethod = Bindings.findMethodInHierarchy(type, name, paramTypes);
		}
		else
		{
			origMethod = Bindings.findMethodInType(type, name, paramTypes);
		}

		return origMethod;
	}

	public static IMethodBinding findRealMethodInType(final ITypeBinding type, final String name,
			final ITypeBinding[] paramTypes)
	{
		IMethodBinding origMethod = findMethodInType(type, name, paramTypes);

		if( origMethod == null && type.getTypeArguments().length != 0  )
		{
			// no method matches exactly, there could be type arguments (which we don't handle yet)
			origMethod = findMethodInType(type, name, null); // match without params

			if( origMethod != null && origMethod.getParameterTypes().length != paramTypes.length)
			{
				origMethod = null;
			}
		}

		return origMethod;
	}
}
