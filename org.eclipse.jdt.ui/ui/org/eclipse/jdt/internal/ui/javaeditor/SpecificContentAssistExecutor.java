/*******************************************************************************
 * Copyright (c) 2005 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.jdt.internal.ui.javaeditor;

import java.util.Collection;
import java.util.Iterator;

import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.source.ISourceViewer;

import org.eclipse.ui.texteditor.ITextEditor;

import org.eclipse.jdt.internal.corext.Assert;

import org.eclipse.jdt.internal.ui.text.java.CompletionProposalCategory;
import org.eclipse.jdt.internal.ui.text.java.CompletionProposalComputerRegistry;

/**
 * 
 * @since 3.2
 */
public final class SpecificContentAssistExecutor {

	private final CompletionProposalComputerRegistry fRegistry;

	public SpecificContentAssistExecutor(CompletionProposalComputerRegistry registry) {
		Assert.isNotNull(registry);
		fRegistry= registry;
	}

	/**
	 * Invokes content assist on <code>editor</code>, showing only proposals computed by the
	 * <code>CompletionProposalCategory</code> with the given <code>categoryId</code>.
	 * 
	 * @param editor the editor to invoke code assist on
	 * @param categoryId the id of the proposal category to show proposals for
	 */
	public void invokeContentAssist(final ITextEditor editor, String categoryId) {
		Collection categories= fRegistry.getProposalCategories();
		boolean[] inclusionState= new boolean[categories.size()];
		boolean[] separateState= new boolean[categories.size()];
		int i= 0;
		for (Iterator it= categories.iterator(); it.hasNext(); i++) {
			CompletionProposalCategory cat= (CompletionProposalCategory) it.next();
			inclusionState[i]= cat.isIncluded();
			cat.setIncluded(cat.getId().equals(categoryId));
			separateState[i]= cat.isSeparateCommand();
			cat.setSeparateCommand(false);
		}
		
		try {
			ITextOperationTarget target= (ITextOperationTarget) editor.getAdapter(ITextOperationTarget.class);
			if (target != null && target.canDoOperation(ISourceViewer.CONTENTASSIST_PROPOSALS))
				target.doOperation(ISourceViewer.CONTENTASSIST_PROPOSALS);
		} finally {
			i= 0;
			for (Iterator it= categories.iterator(); it.hasNext(); i++) {
				CompletionProposalCategory cat= (CompletionProposalCategory) it.next();
				cat.setIncluded(inclusionState[i]);
				cat.setSeparateCommand(separateState[i]);
			}
		}
	}
}
