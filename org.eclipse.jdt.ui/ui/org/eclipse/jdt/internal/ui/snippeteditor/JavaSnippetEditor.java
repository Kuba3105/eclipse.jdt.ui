package org.eclipse.jdt.internal.ui.snippeteditor;
/*
 * Licensed Materials - Property of IBM,
 * WebSphere Studio Workbench
 * (c) Copyright IBM Corp 1999, 2000
 */


import java.io.ByteArrayOutputStream;import java.io.PrintStream;import java.lang.reflect.InvocationTargetException;import java.util.ArrayList;import java.util.Collections;import java.util.List;import java.util.ResourceBundle;import org.eclipse.core.resources.IFile;import org.eclipse.core.resources.IMarker;import org.eclipse.core.resources.IProject;import org.eclipse.core.resources.IncrementalProjectBuilder;import org.eclipse.core.runtime.CoreException;import org.eclipse.core.runtime.IProgressMonitor;import org.eclipse.core.runtime.IStatus;import org.eclipse.debug.core.DebugEvent;import org.eclipse.debug.core.DebugException;import org.eclipse.debug.core.DebugPlugin;import org.eclipse.debug.core.IDebugEventListener;import org.eclipse.debug.core.ILaunchManager;import org.eclipse.debug.core.ILauncher;import org.eclipse.debug.core.model.IDebugElement;import org.eclipse.debug.core.model.IDebugTarget;import org.eclipse.debug.ui.DebugUITools;import org.eclipse.jdt.core.IJavaElement;import org.eclipse.jdt.core.IJavaProject;import org.eclipse.jdt.core.JavaCore;import org.eclipse.jdt.core.JavaModelException;import org.eclipse.jdt.core.eval.IEvaluationContext;import org.eclipse.jdt.debug.core.IJavaEvaluationListener;import org.eclipse.jdt.debug.core.IJavaEvaluationResult;import org.eclipse.jdt.debug.core.IJavaStackFrame;import org.eclipse.jdt.debug.core.IJavaThread;import org.eclipse.jdt.debug.core.IJavaValue;import org.eclipse.jdt.internal.ui.JavaPlugin;import org.eclipse.jdt.internal.ui.text.java.ResultCollector;import org.eclipse.jdt.launching.JavaRuntime;import org.eclipse.jdt.ui.IContextMenuConstants;import org.eclipse.jdt.ui.text.JavaTextTools;import org.eclipse.jface.action.Action;import org.eclipse.jface.action.IMenuManager;import org.eclipse.jface.dialogs.ErrorDialog;import org.eclipse.jface.dialogs.MessageDialog;import org.eclipse.jface.dialogs.ProgressMonitorDialog;import org.eclipse.jface.operation.IRunnableWithProgress;import org.eclipse.jface.text.BadLocationException;import org.eclipse.jface.text.IDocument;import org.eclipse.jface.text.ITextSelection;import org.eclipse.jface.text.source.ISourceViewer;import org.eclipse.jface.util.Assert;import org.eclipse.swt.widgets.Control;import org.eclipse.swt.widgets.Shell;import org.eclipse.ui.IEditorSite;import org.eclipse.ui.IFileEditorInput;import org.eclipse.ui.part.EditorActionBarContributor;import org.eclipse.ui.part.FileEditorInput;import org.eclipse.ui.texteditor.AbstractTextEditor;import org.eclipse.ui.texteditor.ITextEditorActionConstants;import org.eclipse.ui.texteditor.MarkerUtilities;import org.eclipse.ui.texteditor.TextOperationAction;import com.sun.jdi.InvocationException;import com.sun.jdi.ObjectReference;

/**
 * An editor for Java snippets.
 */
public class JavaSnippetEditor extends AbstractTextEditor implements IDebugEventListener, IJavaEvaluationListener {			

	public static final String PREFIX = "SnippetEditor.";
	public static final String ERROR = PREFIX + "error.";
	public static final String EVALUATING = PREFIX + "evaluating";

	private final static String TAG= "input_element";
	
	final static int RESULT_DISPLAY= 1;
	final static int RESULT_RUN= 2;
	final static int RESULT_INSPECT= 3;
	
	private IJavaProject fJavaProject;
	private IEvaluationContext fEvaluationContext;
	private IDebugTarget fVM;
	private String[] fLaunchedClassPath;
	private List fSnippetStateListeners;	
	private int fResultMode; // one of the RESULT_* constants from above
	private boolean fEvaluating;
	private IJavaThread fThread;
	
	private int fSnippetStart;
	private int fSnippetEnd;
	
	private String fPackageName= null;
	
	/**
	 * Default constructor.
	 */
	public JavaSnippetEditor() {
		super();
		setDocumentProvider(JavaPlugin.getDefault().getSnippetDocumentProvider());
		JavaTextTools textTools= JavaPlugin.getDefault().getJavaTextTools();
		setSourceViewerConfiguration(new JavaSnippetViewerConfiguration(textTools, this));		
		fSnippetStateListeners= new ArrayList(4);
	}
		
	public void dispose() {
		shutDownVM();
		fSnippetStateListeners= Collections.EMPTY_LIST;
		super.dispose();
	}
	/** 
	 * Returns the editor's resource bundle.
	 *
	 * @return the editor's resource bundle
	 */
	protected ResourceBundle getResourceBundle() {
		return JavaPlugin.getDefault().getResourceBundle();
	}
	
	/**
	 * Convenience method for safely accessing resources.
	 */
	protected String getResourceString(String key) {
		return JavaPlugin.getDefault().getResourceString(key);
	}
	
	/**
	 * @see AbstractTextEditor#createActions
	 */
	protected void createActions() {
		super.createActions();
		setAction("Display", new DisplayAction(this));		
		setAction("Run", new RunAction(this));
		setAction("Inspect", new InspectAction(this));
		
		Action a= new StopAction(this);
		a.setEnabled(false);
		setAction("Stop", a);

		setAction("RunInPackage", new RunInPackageAction(this));
		setAction("Import", new ImportAction(this));
		setAction("ContentAssistProposal", new TextOperationAction(getResourceBundle(), "Editor.ContentAssistProposal.", this, ISourceViewer.CONTENTASSIST_PROPOSALS));			
		setAction("OpenOnSelection", new SnippetOpenOnSelectionAction(this, getResourceBundle(), "Editor.OpenOnSelection."));			
	}
	
	/**
	 * @see IEditorPart#saveState(IMemento)
	 */
	/*public void saveState(IMemento memento) {
		IFile file= (IFile) getEditorInput();
		memento.putString(TAG, file.getFullPath().toString());
	}*/
	
	/**
	 * @see AbstractTextEditor#editorContextMenuAboutToShow(MenuManager)
	 */
	public void editorContextMenuAboutToShow(IMenuManager menu) {
		super.editorContextMenuAboutToShow(menu);
		addGroup(menu, ITextEditorActionConstants.GROUP_EDIT, IContextMenuConstants.GROUP_GENERATE);		
		addGroup(menu, ITextEditorActionConstants.GROUP_FIND, IContextMenuConstants.GROUP_SEARCH);		
		addAction(menu, IContextMenuConstants.GROUP_GENERATE, "ContentAssistProposal");
		addAction(menu, IContextMenuConstants.GROUP_GENERATE, "OpenOnSelection");
		addAction(menu, IContextMenuConstants.GROUP_SEARCH, "Display");
		addAction(menu, IContextMenuConstants.GROUP_SEARCH, "Run");
		addAction(menu, IContextMenuConstants.GROUP_SEARCH, "Inspect");
	}

	public boolean isVMLaunched() {
		return fVM != null;
	}
	
	public boolean isEvaluating() {
		return fEvaluating;
	}
	
	public void evalSelection(final int resultMode) {
		IRunnableWithProgress r= new IRunnableWithProgress() {
			public void run(IProgressMonitor pm) throws InvocationTargetException {
				pm.beginTask(JavaPlugin.getResourceString(EVALUATING), IProgressMonitor.UNKNOWN);
				if (!getJavaProject().getProject().getWorkspace().isAutoBuilding()) {
					try {
						getJavaProject().getProject().build(IncrementalProjectBuilder.INCREMENTAL_BUILD, pm);
					} catch (CoreException e) {
						throw new InvocationTargetException(e);
					}
				}
				fResultMode= resultMode;
		
				if (classPathHasChanged()) {
					// need to relaunch VM
				};
			
				fVM = ScrapbookLauncher.getDefault().getDebugTarget(getPage());
				if (fVM == null) {
					launchVM();
					fVM = ScrapbookLauncher.getDefault().getDebugTarget(getPage());
				}
			}
		};
		try {
			new ProgressMonitorDialog(getShell()).run(true, false, r);		
		} catch (InterruptedException e) {
			evaluationEnds();
			return;
		} catch (InvocationTargetException e) {
			evaluationEnds();
			return;
		}
		if (fVM == null) {
			return;
		}
		fireEvalStateChanged();
		DebugPlugin.getDefault().addDebugEventListener(JavaSnippetEditor.this);

		ITextSelection selection= (ITextSelection) getSelectionProvider().getSelection();
		String snippet= selection.getText();
		if (snippet.length() == 0) {
			return;
		}
		fSnippetStart= selection.getOffset();
		fSnippetEnd= fSnippetStart + selection.getLength();
		
		evaluationStarts();
		evaluate(snippet);			
	}	
	
	public void setPackage(String packageName) {
		fPackageName= packageName;
	}
			
	protected IEvaluationContext getEvaluationContext() {
		if (fEvaluationContext == null) {
			IJavaProject project= getJavaProject();
			fEvaluationContext= project.newEvaluationContext();
		}
		if (fPackageName != null) {		
			fEvaluationContext.setPackageName(fPackageName);
		}
		return fEvaluationContext;
	}
	
	public IJavaProject getJavaProject() {
		if (fJavaProject == null) {
			try {
				fJavaProject = findJavaProject();
			} catch (JavaModelException e) {
				showError(e.getStatus());
			}
		}
		return fJavaProject;
	}
	
	public void shutDownVM() {
		DebugPlugin.getDefault().removeDebugEventListener(this);

		// The real shut down
		if (fVM != null) {
			try {
				fVM.terminate();
			} catch (DebugException e) {
				ErrorDialog.openError(getShell(), JavaPlugin.getResourceString(ERROR + "cantshutdown"), null, e.getStatus());
				return;
			}
			DebugPlugin.getDefault().getLaunchManager().deregisterLaunch(fVM.getLaunch());
			fVM= null;
			fThread = null;
			fEvaluationContext= null;
			Action action= (Action)getAction("Stop");
			action.setEnabled(false);
			fireEvalStateChanged();
		}
	}
	
	public void addSnippetStateChangedListener(ISnippetStateChangedListener listener) {
		if (!fSnippetStateListeners.contains(listener))
			fSnippetStateListeners.add(listener);
	}
	
	public void removeSnippetStateChangedListener(ISnippetStateChangedListener listener) {
		if (fSnippetStateListeners != null)
			fSnippetStateListeners.remove(listener);
	}

	public void fireEvalStateChanged() {
		Runnable r= new Runnable() {
			public void run() {			
				List v= new ArrayList(fSnippetStateListeners);
				for (int i= 0; i < v.size(); i++) {
					ISnippetStateChangedListener l= (ISnippetStateChangedListener) v.get(i);
					l.snippetStateChanged(JavaSnippetEditor.this);
				}
			}
		};
		getShell().getDisplay().asyncExec(r);
	}
	
	void evaluate(final String snippet) {
		if (getThread() == null) {
			// repost - wait for our main thread to suspend
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
			}
			Runnable r = new Runnable() {
				public void run() {
					evaluate(snippet);
				}
			};
			getShell().getDisplay().asyncExec(r);
			return;
		}	
		try {
			getThread().evaluate(snippet, JavaSnippetEditor.this, getEvaluationContext());
		} catch (DebugException e) {
			ErrorDialog.openError(getShell(), JavaPlugin.getResourceString(ERROR + "problemseval"), null, e.getStatus());
			evaluationEnds();
		} finally {
			try {
				fThread.resume();
			} catch (DebugException e) {
				// XXX: error
			}
		}
	}

	public void evaluationComplete(final IJavaEvaluationResult result) {
		Runnable r = new Runnable() {
			public void run() {
				evaluationEnds();
				if (result.hasProblems()) {
					IMarker[] problems = result.getProblems();
					int count= problems.length;
					if (count == 0) {
						showException(result.getException());
						return;
					} else {
						for (int i = 0; i < count; i++) {
							showProblem(problems[i]);
						}
						return;
					}
				}
				final IJavaValue value = result.getValue();
				if (value != null) {
					switch (fResultMode) {
					case RESULT_DISPLAY:
						Runnable r = new Runnable() {
							public void run() {
								displayResult(value);
							}
						};
						getSite().getShell().getDisplay().asyncExec(r);
						break;
					case RESULT_INSPECT:
						String snippet = result.getSnippet().trim();
						int snippetLength = snippet.length();
						if (snippetLength > 30) {
							snippet = snippet.substring(0, 15) + "..." + snippet.substring(snippetLength - 15, snippetLength); 
						}
						snippet = snippet.replace('\n', ' ');
						snippet = snippet.replace('\r', ' ');
						snippet = snippet.replace('\t', ' ');
						DebugUITools.inspect(snippet, value);
						break;
					case RESULT_RUN:
						// no action
						break;
					}
				}
			}
		};
		Control control= getVerticalRuler().getControl();
		if (!control.isDisposed()) {
			control.getDisplay().asyncExec(r);
		}
	}
	
	public void codeComplete(ResultCollector collector) throws JavaModelException {
		IDocument d= getSourceViewer().getDocument();
		ITextSelection selection= (ITextSelection)getSelectionProvider().getSelection();
		int start= selection.getOffset();
		String snippet= d.get();	
		IEvaluationContext e= getEvaluationContext();
		if (e != null) 
			e.codeComplete(snippet, start, collector);
	}
		 
	public IJavaElement[] codeResolve() throws JavaModelException {
		IDocument d= getSourceViewer().getDocument();
		ITextSelection selection= (ITextSelection) getSelectionProvider().getSelection();
		int start= selection.getOffset();
		int len= selection.getLength();
		
		String snippet= d.get();	
		IEvaluationContext e= getEvaluationContext();
		if (e != null) 
			return e.codeSelect(snippet, start, len);
		return null;
	}	
	public void showError(IStatus status) {
		evaluationEnds();
		if (!status.isOK())
			ErrorDialog.openError(getShell(), JavaPlugin.getResourceString(ERROR + "erroreval"), null, status);
	}
	
	public void displayResult(IJavaValue result) {
		StringBuffer resultString= new StringBuffer();
		try {
			String sig= result.getSignature();
			if ("V".equals(sig)) {
				resultString.append(' ');
				resultString.append(JavaPlugin.getResourceString(ERROR + "noreturn"));
			} else {
				if (sig != null) {
					resultString.append(" (");
					resultString.append(result.getReferenceTypeName());
					resultString.append(") ");
				} else {
					resultString.append(' ');
				}   
				resultString.append(result.evaluateToString());
			}
		} catch(DebugException e) {
			ErrorDialog.openError(getShell(), JavaPlugin.getResourceString(ERROR + "evaltostring"), null, e.getStatus());
		}
			
		try {
			getSourceViewer().getDocument().replace(fSnippetEnd, 0, resultString.toString());
		} catch (BadLocationException e) {
		}
		
		selectAndReveal(fSnippetEnd, resultString.length());
	}
	
	protected void showProblem(IMarker problem) {
		int estart= MarkerUtilities.getCharStart(problem)+fSnippetStart;
		String message= JavaPlugin.getResourceString(ERROR + "unqualified");
		message= problem.getAttribute(IMarker.MESSAGE, message);
		try {
			getSourceViewer().getDocument().replace(estart, 0, message);
		} catch (BadLocationException e) {
		}
		selectAndReveal(estart, message.length());
	}
	
	protected void showException(Throwable exception) {
		if (exception instanceof DebugException) {
			showException((DebugException)exception);
			return;
		}
		ByteArrayOutputStream bos= new ByteArrayOutputStream();
		PrintStream ps= new java.io.PrintStream(bos, true);
		exception.printStackTrace(ps);
		try {
			getSourceViewer().getDocument().replace(fSnippetEnd, 0, bos.toString());
		} catch (BadLocationException e) {
		}
		selectAndReveal(fSnippetEnd, bos.size());
	}
	
	protected void showException(DebugException exception) {
		IStatus status= exception.getStatus();
		Throwable t= status.getException();
		if (t instanceof com.sun.jdi.InvocationException) {
			InvocationException ie= (InvocationException)t;
			ObjectReference ref= ie.exception();
			String eName= ref.referenceType().name();
			try {
				getSourceViewer().getDocument().replace(fSnippetEnd, 0, getResourceString(ERROR + "exceptioneval") + eName);
			} catch (BadLocationException e) {
			}
			
		} else {
			showException(t);
		}
	}
	
	IJavaProject findJavaProject() throws JavaModelException {
		Object input= getEditorInput();
		if (input instanceof IFileEditorInput) {
			IFileEditorInput file= (IFileEditorInput)input;
			IProject p= file.getFile().getProject();
			return JavaCore.create(p);
		}
		Assert.isTrue(false, "no Java project found for snippet");
		return null;
	}
		
	boolean classPathHasChanged() {
		String[] classpath= getClassPath(getJavaProject());
		if (fLaunchedClassPath != null && !classPathsEqual(fLaunchedClassPath, classpath)) {
			MessageDialog.openError(getShell(), JavaPlugin.getResourceString(ERROR + "warningdialogtitle"), JavaPlugin.getResourceString(ERROR + "cpchanged"));
			return true;
		}
		return false;
	}
	
	boolean classPathsEqual(String[] path1, String[] path2) {
		if (path1.length != path2.length)
			return false;
		for (int i= 0; i < path1.length; i++) {
			if (!path1[i].equals(path2[i]))
				return false;
		}
		return true;
	}
		
	void evaluationStarts() {
		fEvaluating= true;
		fireEvalStateChanged();
		showStatus(JavaPlugin.getResourceString(EVALUATING));
		getSourceViewer().setEditable(false);
	}
	
	void evaluationEnds() {
		fEvaluating= false;
		fireEvalStateChanged();
		showStatus("");
		getSourceViewer().setEditable(true);
	}
	
	void showStatus(String message) {
		IEditorSite site=(IEditorSite)getSite();
		EditorActionBarContributor contributor= (EditorActionBarContributor)site.getActionBarContributor();
		contributor.getActionBars().getStatusLineManager().setMessage(message);
	}
	
	String[] getClassPath(IJavaProject project) {
		try {
			return JavaRuntime.computeDefaultRuntimeClassPath(project);
		} catch (CoreException e) {
			return new String[0];
		}
	}
	
	protected Shell getShell() {
		return getSite().getShell();
	}
	
	public void handleDebugEvent(DebugEvent e) {
		Object source = e.getSource();
		if (source instanceof IDebugElement) {
			IDebugElement de = (IDebugElement)source;
			if (de.getElementType() == IDebugElement.DEBUG_TARGET) {
				if (de.getDebugTarget().equals(fVM)) {
					if (e.getKind() == DebugEvent.TERMINATE) {
						shutDownVM();
					}
				}
			}
		}
	}
	
	protected IJavaThread getThread() {
		try {
			if (fThread == null) {
				IDebugElement[] threads = fVM.getChildren();
				for (int i = 0; i < threads.length; i++) {
					IJavaThread thread = (IJavaThread)threads[i];
					if (thread.isSuspended() && thread.getTopStackFrame().getLineNumber() == 60) {
						IJavaStackFrame frame = (IJavaStackFrame)thread.getTopStackFrame();
						if (frame.getMethodName().equals("nop")) {
							fThread = thread;
							break;
						}
					}
				}
			}
		} catch(DebugException e) {
			JavaPlugin.log(e.getStatus());
			return null;
		}
		return fThread;
	}
	
	protected void launchVM() {
		ILauncher launcher = ScrapbookLauncher.getLauncher();
		launcher.launch(new Object[] {getPage()}, ILaunchManager.DEBUG_MODE);
	}
	
	protected IFile getPage() {
		return ((FileEditorInput)getEditorInput()).getFile();
	}
	
	/**
	 * Updates all selection dependent actions.
	 */
	protected void updateSelectionDependentActions() {
		super.updateSelectionDependentActions();
		fireEvalStateChanged();
	}
}