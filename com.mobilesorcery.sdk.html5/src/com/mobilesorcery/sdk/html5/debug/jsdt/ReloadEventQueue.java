package com.mobilesorcery.sdk.html5.debug.jsdt;

import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.wst.jsdt.debug.core.breakpoints.IJavaScriptLineBreakpoint;
import org.eclipse.wst.jsdt.debug.core.jsdi.Location;
import org.eclipse.wst.jsdt.debug.core.jsdi.event.EventQueue;
import org.eclipse.wst.jsdt.debug.core.jsdi.event.EventSet;
import org.json.simple.JSONObject;

import com.mobilesorcery.sdk.core.Pair;
import com.mobilesorcery.sdk.core.Util;
import com.mobilesorcery.sdk.html5.Html5Plugin;
import com.mobilesorcery.sdk.html5.live.LiveServer;

public class ReloadEventQueue implements EventQueue {

	public final static String CUSTOM_EVENT = "custom-event";

	private final ReloadVirtualMachine vm;
	private final ReloadEventRequestManager requests;

	private final LinkedBlockingQueue<Pair<String, Object>> internalQueue = new LinkedBlockingQueue<Pair<String, Object>>();

	public ReloadEventQueue(ReloadVirtualMachine vm,
			ReloadEventRequestManager requests) {
		this.vm = vm;
		this.requests = requests;
	}

	@Override
	public EventSet remove() {
		return remove(-1);
	}

	@Override
	public EventSet remove(int timeout) {
		try {
			// TODO: EVENTS SHOULD BE PURE JSON!?
			Pair<String, Object> event = timeout > 0 ? internalQueue.poll(
					timeout, TimeUnit.MILLISECONDS) : internalQueue.take();
			return handleEvent(event.first, event.second);
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private EventSet handleEvent(String commandName, Object commandObj) {
		ReloadEventSet eventSet = new ReloadEventSet(vm);
		List bpRequests = requests.breakpointRequests();
		List stepRequests = requests.stepRequests();

		boolean isStepping = false;

		ReloadVirtualMachine targetVM = extractVM(commandObj);

		if (targetVM == this.vm) {
			if ("report-breakpoint".equals(commandName)) {
				// Breakpoint!
				JSONObject command = (JSONObject) commandObj;
				ReloadThreadReference thread = (ReloadThreadReference) vm
						.allThreads().get(0);
				String fileName = (String) command.get("file");
				Long line = (Long) command.get("line");
				isStepping = command.containsKey("step")
						&& (Boolean) command.get("step");
				if (fileName != null && line != null) {
					IJavaScriptLineBreakpoint bp = LiveServer.findBreakPoint(
							new Path(fileName), line);
					for (Object bpRequestObj : bpRequests) {
						ReloadBreakpointRequest bpRequest = (ReloadBreakpointRequest) bpRequestObj;
						Location location = bpRequest.location();
						if (!isStepping && sameLocation(location, bp)) {
							eventSet.add(new ReloadBreakpointEvent(vm, thread,
									location, bpRequest));
						}
					}
					for (Object stepRequestObj : stepRequests) {
						ReloadStepRequest stepRequest = (ReloadStepRequest) stepRequestObj;
						IFile file = ResourcesPlugin.getWorkspace().getRoot()
								.getFile(new Path(fileName));
						Location location = new SimpleLocation(vm, file,
								line.intValue());
						eventSet.add(new ReloadStepEvent(vm, thread, location,
								stepRequest));
					}
				}
				if (!eventSet.isEmpty()) {
					thread.suspend(!isStepping);
				}
			} else if (CUSTOM_EVENT.equals(commandName)) {
				ReloadEvent event = (ReloadEvent) commandObj;
				eventSet.add(event);
			}
		}
		return eventSet;
	}

	private ReloadVirtualMachine extractVM(Object commandObj) {
		if (commandObj instanceof JSONObject) {
			Integer sessionId = LiveServer.extractSessionId((JSONObject) commandObj);
			if (sessionId != null) {
				return Html5Plugin.getDefault().getReloadServer().getVM(sessionId);
			}
		} else if (commandObj instanceof ReloadEvent) {
			return (ReloadVirtualMachine) ((ReloadEvent) commandObj)
					.virtualMachine();
		}
		return null;
	}

	private boolean sameLocation(Location location, IJavaScriptLineBreakpoint bp) {
		if (bp == null || !(location instanceof SimpleLocation)) {
			return false;
		}
		try {
			if (bp.getLineNumber() == location.lineNumber()) {
				IPath path1 = ((SimpleScriptReference) location
						.scriptReference()).getFile().getFullPath();
				IPath path2 = new Path(bp.getScriptPath());
				return Util.equals(path1, path2);
			}
		} catch (CoreException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		return false;
	}

	public void received(String command, Object data) {
		internalQueue.offer(new Pair<String, Object>(command, data));
	}
}
