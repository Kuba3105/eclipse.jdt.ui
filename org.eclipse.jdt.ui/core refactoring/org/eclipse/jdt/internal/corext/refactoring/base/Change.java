/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.corext.refactoring.base;


import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.core.runtime.IProgressMonitor;

import org.eclipse.jdt.core.JavaModelException;

import org.eclipse.jdt.internal.corext.Assert;

/**
 * Represents a generic change to the workbench. An <code>Change</code> object 
 * is typically created by calling <code>Refactoring.createChange()</code>.
 * <p>
 * Changes are best executed by using a {@link PerformChangeOperation}. If clients
 * execute a change directly then the following life cycle has to be honored:
 * <ul>
 *   <li>after a single change or a tree of changes has been created, the
 *       method <code>initializeValidationState</code> has to be called.</li>
 *   <li>the method <code>isValid</code> can be used to determine if a change
 *       can still be applied to the workspace. If the method returns a {@link 
 *       RefactoringStatus} with a severity of FATAL then the change has to be 
 *       treated as invalid. Performing an invalid change isn't allowed and 
 *       results in an unspecified result. This method can be called multiple
 *       times.
 *   <li>then the method perform can be called. An disabled change should not
 *       be executed.</li>
 *   <li>the method dispose has to be called either after the perform method
 *       has been called or if a change is no longer needed. The second case
 *       for example occurrs when the undo stack gets flushed and all change
 *       objects managed by the undo stack are no longer needed. The method
 *       dispose is typically use to unregister listeners register during the
 *       method <code>initializeValidationState</code>. There is no guarantee 
 *       that <code>initializeValidationState</code>, <code>isValid</code>
 *       or <code>perform</code> has been called, before <code>dispose</code>
 *       is called.
 * </ul>
 * Below a code snippet that can be used to execute a change:
 * <pre>
 *   Change change= createChange();
 *   try {
 *     change.initializeValidationState(pm);
 * 
 *     ....
 * 
 *     if (!change.isEnabled())
 *         return;
 *     RefactoringStatus valid= change.isValid(new SubProgressMonitor(pm, 1));
 *     if (valid.hasFatalError())
 *         return;
 *     Change undo= change.perform(new SubProgressMonitor(pm, 1));
 *     if (undo != null) {
 *        undo.initializeValidationState(new SubProgressMonitor(pm, 1));
 *        // do something with the undo object
 *     }
 *   } finally {
 *     change.dispose();
 *   }
 * </pre>
 * </p>
 * <p>
 * It is important that implementors of this abstract class provide an adequat 
 * implementation of <code>isValid</code> and that they provide an undo change
 * via the return value of the method <code>perform</code>. If no undo can be
 * provided then the perform method is allowed to return <code>null</code>. But
 * implementors should be aware that not providing an undo object for a change 
 * object that is part of a larger change tree will result in the fact that for
 * the whole change tree no undo object will be present.    
 * </p>
 * <p>
 * <bf>NOTE:<bf> This class/interface is part of an interim API that is still under development 
 * and expected to change significantly before reaching stability. It is being made available at 
 * this early stage to solicit feedback from pioneering adopters on the understanding that any 
 * code that uses this API will almost certainly be broken (repeatedly) as the API evolves.
 * </p>
 * 
 * @since 3.0
 */
public abstract class Change implements IAdaptable {

	private Change fParent;
	private boolean fIsEnabled= true;
	
	/**
	 * Constructs a new change object.
	 */
	protected Change() {
	}
	
	/**
	 * Returns the human readable name of this change. The
	 * name <em>MUST</em> not be <code>null</code>.
	 * 
	 * @return the human readable name of this change
	 */
	public abstract String getName();
	
	/**
	 * Returns whether this change is enabled or not. Disabled changes
	 * must not be executed.
	 *
	 * @return <code>true</code> if the change is enabled; <code>false</code>
	 *  otherwise.
	 */
	public boolean isEnabled() {
		return fIsEnabled;
	}
	
	/**
	 * Sets whether this change is enabled or not.
	 *
	 * @param enabled <code>true</code> to enable this change; <code>
	 *  false</code> otherwise
	 */
	public void setEnabled(boolean enabled) {
		fIsEnabled= enabled;
	}
	
	/**
	 * Returns the parent change. Returns <code>null</code> if no
	 * parent exists.
	 * 
	 * @return the parent change
	 */
	public Change getParent() {
		return fParent;
	}
	
	/**
	 * Sets the parent of this change. Requires that this change isn't already
	 * connected to a parent. The parent can be <code>null</code> to disconnect
	 * this change from a parent.
	 * 
	 * @param parent the parent of this change or <code>null</code>
	 */
	public void setParent(Change parent) {
		if (parent != null)
			Assert.isTrue(fParent == null);
		fParent= parent;
	}
	
	/**
	 * Hook method to initialize some internal state to provide an adequat answer
	 * for the <code>isValid</code> method. This method gets called after a change
	 * or a whole change tree has been created. 
	 * <p>
	 * Typically this method is implemented in one of the following ways: 
	 * <ul>
	 *   <li>the change hooks up a listener on some delta notification mechanism
	 *       and marks itself as invalid if it receives a certain delta. Is this 
	 *       the case the implementor must take care of unhooking the listener
	 *       in <code>dispose</code>.</li>
	 *   <li>the change remembers some information allowing to decide if a change
	 *       object is still valid when <code>isValid</code> is called.</li>
	 * </ul>
	 * <p>
	 * For example, a change object that manipulates the content of an <code>IFile</code>
	 * could either listen to resource changes and detect that the file got changed or
	 * it could remember the timestamp and compare it with the actual timestamp when
	 * <code>isValid</code> is called.
	 * </p>
	 * 
	 * @param pm a progress monitor
	 * 
	 * @throws CoreException if some error occurred while initializing the validation
	 *  state. In this case the change object has to be treated as invalid
	 */
	public abstract void initializeValidationData(IProgressMonitor pm) throws CoreException;
	
	/**
	 * Verifies that this change object is still valid and can be executed by calling 
	 * <code>perform</code>. If a refactoring status  with a severity of {@link 
	 * RefactoringStatus#FATAL} is returned then the change has to be treated as invalid 
	 * and can no longer be executed. Performing such a change produces an unspecified 
	 * result and will very likely throw an exception.
	 * <p>
	 * This method is also called by the {@link IUndoManager UndoManager} to decide if
	 * an undo or redo change is still valid and therefore can be executed.
	 * </p>
	 * <p>
	 * This method can be called multiple times before a change gets executed.
	 * </p>
	 * 
	 * @param pm a progress monitor.
	 * 
	 * @return a refactoring status describing the outcome of the validation check
	 * 
	 * @throws CoreException if an error occured during validation check. The change
	 *  is to be treated as invalid if an exception occurs
	 */
	public abstract RefactoringStatus isValid(IProgressMonitor pm) throws CoreException;
	
	/**
	 * Performs this change. If this method is call on an invalid or disabled change 
	 * object the result is unspecified.  
	 * 
	 * @param pm a progress monitor
	 * 
	 * @return the undo change for this change object or <code>null</code> if no
	 *  undo is provided
	 * 
	 * @throws CoreException if an error occurred during change execution
	 */
	public abstract Change perform(IProgressMonitor pm) throws CoreException;
	
	public void dispose() {
		// empty default implementation
	}
	 
	/**
	 * Returns the language element modified by this <code>IChange</code>. The method
	 * may return <code>null</code> if the change isn't related to a language element.
	 * 
	 * @return the language element modified by this change
	 */
	public abstract Object getModifiedElement();
	
	/**
	 * {@inheritDoc}
	 */
	public Object getAdapter(Class adapter) {
		if (fParent == null)
			return null;
		return fParent.getAdapter(adapter);
	}

	/**
	 * @deprecated this method will go away and subclasses should no longer
	 *  overide it
	 */
	public final RefactoringStatus aboutToPerform(ChangeContext context, IProgressMonitor pm) {
		Assert.isTrue(false, "Can no longer be called");
		return new RefactoringStatus();
	}
	
	/**
	 * @deprecated this method will go away and subclasses should no longer
	 *  overide it
	 */
	public final Change getUndoChange() {
		Assert.isTrue(false, "Can no longer be called");
		return null;
	}

	/**
	 * @deprecated this method will go away and subclasses should no longer
	 *  overide it
	 */
	public  final boolean isUndoable() {
		Assert.isTrue(false, "Can no longer be called");
		return false;
	}
	
	/**
	 * @deprecated this method will go away and subclasses should no longer
	 *  overide it
	 */
	public final void perform(ChangeContext context, IProgressMonitor pm) throws JavaModelException, ChangeAbortException {
		Assert.isTrue(false, "Can no longer be called");
	}
	
	/**
	 * @deprecated this method will go away and subclasses should no longer
	 *  overide it
	 */
	public final void performedd() {
		Assert.isTrue(false, "Can no longer be called");
	}	
}
