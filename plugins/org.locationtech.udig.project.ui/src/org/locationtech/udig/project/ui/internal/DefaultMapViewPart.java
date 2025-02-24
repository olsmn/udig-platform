/**
 * uDig - User Friendly Desktop Internet GIS client
 * http://udig.refractions.net
 * (C) 2021, Refractions Research Inc.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * (http://www.eclipse.org/legal/epl-v10.html), and the Refractions BSD
 * License v1.0 (http://udig.refractions.net/files/bsd3-v10.html).
 */
package org.locationtech.udig.project.ui.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.IMenuListener;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.IToolBarManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.viewers.StructuredSelection;
import org.eclipse.swt.SWT;
import org.eclipse.swt.dnd.DropTargetEvent;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Menu;
import org.eclipse.ui.IActionBars;
import org.eclipse.ui.part.ViewPart;
import org.locationtech.udig.catalog.IGeoResource;
import org.locationtech.udig.internal.ui.IDropTargetProvider;
import org.locationtech.udig.internal.ui.UDIGControlDropListener;
import org.locationtech.udig.internal.ui.UDIGDropHandler;
import org.locationtech.udig.project.IProject;
import org.locationtech.udig.project.internal.Map;
import org.locationtech.udig.project.internal.commands.CreateMapCommand;
import org.locationtech.udig.project.ui.ApplicationGIS;
import org.locationtech.udig.project.ui.tool.IMapEditorSelectionProvider;
import org.locationtech.udig.project.ui.tool.IToolManager;
import org.locationtech.udig.project.ui.viewers.MapEditDomain;
import org.locationtech.udig.project.ui.viewers.MapViewer;
import org.locationtech.udig.ui.UDIGDragDropUtilities;
import org.locationtech.udig.ui.UDIGDragDropUtilities.DropTargetDescriptor;

/**
 * An ViewPart for easily including a map in a view. To use, override getResources to configure the
 * layers in the map. Also override acquireToolbar if the view should acquire a the toolbar when the
 * view has focus. Init map can be overridden to further configure the map.
 *
 * @author jeichar
 *
 * @version 1.3.0
 */
public abstract class DefaultMapViewPart extends ViewPart implements MapPart, IDropTargetProvider {

    protected Map map;

    MapViewer viewer;

    MapSite mapSite;

    private MapEditorSelectionProvider selectionProvider;

    private DropTargetDescriptor dropTarget;

    private IToolManager toolManager;

    private MapEditDomain editDomain;

    /**
     * Returns the map that is to be displayed in the view.
     */
    @Override
    public Map getMap() {
        return viewer.getMap();
    }

    @Override
    public void openContextMenu() {
        viewer.openContextMenu();
    }

    @Override
    public void setFont(Control textArea) {
        viewer.setFont(textArea);
    }

    @Override
    public void setSelectionProvider(IMapEditorSelectionProvider selectionProvider) {
        viewer.setSelectionProvider(selectionProvider);
    }

    @Override
    public final void createPartControl(Composite parent) {
        editDomain = new MapEditDomain(null);
        mapSite = new MapSite(getViewSite(), this);
        try {
            IProgressMonitor monitor = getViewSite().getActionBars().getStatusLineManager()
                    .getProgressMonitor();
            viewer = new MapViewer(parent, this, SWT.DOUBLE_BUFFERED);
            List<IGeoResource> resources = new ArrayList<>();
            createResources(resources, monitor);
            IProject activeProject = ApplicationGIS.getActiveProject();

            CreateMapCommand command = new CreateMapCommand("NewMap", resources, activeProject); //$NON-NLS-1$
            activeProject.sendSync(command);
            Map createdMap = (Map) command.getCreatedMap();
            viewer.setMap(createdMap);
            viewer.init(this);

            // ---------------
            this.selectionProvider = new MapEditorSelectionProvider();

            selectionProvider.setActiveMap(createdMap, this);
            getSite().setSelectionProvider(selectionProvider);

            selectionProvider.setSelection(new StructuredSelection(new Object[] { createdMap }));

            createContextMenu();

            if (acquireToolbar()) {
                toolManager = ApplicationGIS.getToolManager();
                IActionBars bars = getViewSite().getActionBars();
                IToolBarManager toolbarManager = bars.getToolBarManager();
                toolManager.contributeModalTools(toolbarManager, bars);
                toolbarManager.add(new Separator());
                toolManager.contributeActionTools(toolbarManager, bars);
                toolManager.setCurrentEditor(this);
            }
            if (createdMap.getLayersInternal().size() > 0) {
                createdMap.getEditManagerInternal().setSelectedLayer(
                        createdMap.getLayersInternal().get(createdMap.getMapLayers().size() - 1));
            }

            enableDropSupport();

            initMap(createdMap);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(-1);
        }
    }

    /**
     * A hook for configuring the map. The map may already be open and thus if many updates are made
     * consideration about how often the map is re-rendered should be considered. Likely eventing
     * will need to be disabled and after one should manually re-enable them.
     *
     * Default behavior is to simply zoom to extents.
     */
    protected void initMap(Map createdMap) {
        viewer.getMap().getViewportModelInternal().zoomToExtent();
    }

    /**
     * Create the resources to put in the map. If more configuration is required then override
     * {@link #initMap(Map)} and put other configuration in that method
     *
     * @param resources the collection to add the resources to
     * @throws IOException
     */
    protected abstract void createResources(List<IGeoResource> resources, IProgressMonitor monitor)
            throws IOException;

    /**
     * Override and return if the view should acquire the toolbar when focus is set on the viewer.
     */
    protected boolean acquireToolbar() {
        return false;
    }

    /**
     * Passing the focus request to the viewer's control.
     */
    @Override
    public void setFocus() {
        if (acquireToolbar()) {
            toolManager.setCurrentEditor(this);
        }
        viewer.getControl().setFocus();
    }

    @Override
    public Object getTarget(DropTargetEvent event) {
        return this;
    }

    private void enableDropSupport() {
        dropTarget = UDIGDragDropUtilities.addDropSupport(viewer.getViewport().getControl(), this);
    }

    @Override
    public void dispose() {
        super.dispose();
        dropTarget.target.dispose();
    }

    protected void createContextMenu() {
        Menu menu;
        menu = viewer.getMenu();
        if (menu == null) {
            final MenuManager contextMenu = new MenuManager();
            contextMenu.setRemoveAllWhenShown(true);
            contextMenu.addMenuListener(new IMenuListener() {
                @Override
                public void menuAboutToShow(IMenuManager mgr) {
                    IToolManager tm = ApplicationGIS.getToolManager();

                    contextMenu.add(new Separator());
                    contextMenu.add(tm.getBACKWARD_HISTORYAction());
                    contextMenu.add(tm.getFORWARD_HISTORYAction());
                    contextMenu.add(new Separator());
                    contextMenu.add(ApplicationGIS.getToolManager()
                            .createOperationsContextMenu(selectionProvider.getSelection()));
                }
            });

            // Create menu.
            menu = contextMenu.createContextMenu(viewer.getViewport().getControl());
            viewer.setMenu(menu);
            getSite().registerContextMenu(contextMenu, getSite().getSelectionProvider());
        }
    }

    @Override
    public MapSite getMapSite() {
        return mapSite;
    }

    @Override
    public UDIGDropHandler getDropHandler() {
        return ((UDIGControlDropListener) dropTarget.listener).getHandler();
    }

    @Override
    public boolean isDragging() {
        return false;
    }

    @Override
    public void setDragging(boolean isDragging) {
        // ignore drag source
    }
}
