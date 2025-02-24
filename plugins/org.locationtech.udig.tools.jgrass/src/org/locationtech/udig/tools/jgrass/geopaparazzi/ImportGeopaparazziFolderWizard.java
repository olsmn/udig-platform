/**
 * uDig - User Friendly Desktop Internet GIS client
 * (C) HydroloGIS - www.hydrologis.com
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * (http://www.eclipse.org/legal/epl-v10.html), and the HydroloGIS BSD
 * License v1.0 (http://udig.refractions.net/files/hsd3-v10.html).
 */
package org.locationtech.udig.tools.jgrass.geopaparazzi;

import java.io.File;
import java.io.FileInputStream;
import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.wizard.Wizard;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IImportWizard;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;
import org.eclipse.ui.progress.IProgressService;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.feature.DefaultFeatureCollection;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.udig.project.ui.ApplicationGIS;
import org.locationtech.udig.tools.jgrass.JGrassToolsPlugin;
import org.locationtech.udig.ui.ExceptionDetailsDialog;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;

/**
 * The wizard to import for geopaparazzi data.
 *
 * @author Andrea Antonello (www.hydrologis.com)
 */
public class ImportGeopaparazziFolderWizard extends Wizard implements IImportWizard {

    private static final String[] GEOPAPARAZZI_NOTES_DESCRIPTIONFIELDS = { "DESCRIPTION", //$NON-NLS-1$
            "TIMESTAMP", "ALTIM" }; //$NON-NLS-1$ //$NON-NLS-2$

    private static final String GEOPAPARAZZI_NOTES_OUTPUTSHAPEFILENAME = "notes.shp"; //$NON-NLS-1$

    private ImportGeopaparazziFolderWizardPage mainPage;

    private static GeometryFactory gF = new GeometryFactory();

    private CoordinateReferenceSystem mapCrs;

    private boolean canFinish = true;

    public ImportGeopaparazziFolderWizard() {
        super();
    }

    @Override
    public boolean canFinish() {
        return canFinish;
    }

    @Override
    public boolean performFinish() {
        Display.getDefault().asyncExec(new Runnable() {
            @Override
            public void run() {
                try {
                    IWorkbench wb = PlatformUI.getWorkbench();
                    IProgressService ps = wb.getProgressService();
                    ps.busyCursorWhile(new IRunnableWithProgress() {

                        @Override
                        public void run(IProgressMonitor pm) {

                            String geopaparazziFolderPath = mainPage.getGeopaparazziFolderPath();
                            File geopapFolderFile = new File(geopaparazziFolderPath);
                            File geopapDatabaseFile = new File(geopaparazziFolderPath,
                                    "geopaparazzi.db"); //$NON-NLS-1$

                            if (!geopapDatabaseFile.exists()) {
                                MessageDialog.openError(getShell(), "Missing database", //$NON-NLS-1$
                                        "The geopaparazzi database file (geopaparazzi.db) is missing. Check the inserted path."); //$NON-NLS-1$
                                return;
                            }

                            String outputFolderPath = mainPage.getOutputFolderPath();
                            File outputFolderFile = new File(outputFolderPath);

                            mapCrs = ApplicationGIS.getActiveMap().getViewportModel().getCRS();

                            Connection connection = null;
                            try {
                                // create a database connection
                                connection = DriverManager.getConnection(
                                        "jdbc:sqlite:" + geopapDatabaseFile.getAbsolutePath()); //$NON-NLS-1$
                                if (geopapDatabaseFile.exists()) {
                                    /**
                                     * Import notes as shapefile
                                     */
                                    notesToShapefile(connection, outputFolderFile, pm);

                                    /**
                                     * Import gps logs as shapefiles, once as lines and once as
                                     * points
                                     */
                                    gpsLogToShapefiles(connection, outputFolderFile, pm);
                                }
                                /**
                                 * Import media as point shapefile, containin gthe path
                                 */
                                mediaToShapeFile(geopapFolderFile, outputFolderFile, pm);

                            } catch (Exception e) {
                                String message = "An error occurred while importing from geopaparazzi."; //$NON-NLS-1$
                                ExceptionDetailsDialog.openError(null, message, IStatus.ERROR,
                                        JGrassToolsPlugin.PLUGIN_ID, e);
                            } finally {
                                try {
                                    if (connection != null)
                                        connection.close();
                                } catch (SQLException e) {
                                    // connection close failed.
                                    String message = "An error occurred while closing the database connection."; //$NON-NLS-1$
                                    ExceptionDetailsDialog.openError(null, message, IStatus.ERROR,
                                            JGrassToolsPlugin.PLUGIN_ID, e);
                                }
                            }

                        }

                    });
                } catch (Exception e1) {
                    e1.printStackTrace();
                    String message = "An error occurred while extracting the data from the database."; //$NON-NLS-1$
                    ExceptionDetailsDialog.openError(null, message, IStatus.ERROR,
                            JGrassToolsPlugin.PLUGIN_ID, e1);
                }
            }
        });

        return true;
    }

    @Override
    public void init(IWorkbench workbench, IStructuredSelection selection) {
        setWindowTitle("Geopaparazzi Import Wizard"); // NON-NLS-1 //$NON-NLS-1$
        setNeedsProgressMonitor(true);
        mainPage = new ImportGeopaparazziFolderWizardPage("Import GeoPaparazzi Data Folder", //$NON-NLS-1$
                selection);
    }

    @Override
    public void addPages() {
        super.addPages();
        addPage(mainPage);

        // make sure sqlite driver are there
        try {
            Class.forName("org.sqlite.JDBC"); //$NON-NLS-1$
            canFinish = true;
        } catch (Exception e) {
            String message = "An error occurred while loading the database drivers. Check your installation."; //$NON-NLS-1$
            ExceptionDetailsDialog.openError(null, message, IStatus.ERROR,
                    JGrassToolsPlugin.PLUGIN_ID, e);
            canFinish = false;
        }
    }

    private void notesToShapefile(Connection connection, File outputFolderFile, IProgressMonitor pm)
            throws Exception {
        File outputShapeFile = new File(outputFolderFile, GEOPAPARAZZI_NOTES_OUTPUTSHAPEFILENAME);

        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName("geopaparazzinotes"); //$NON-NLS-1$
        b.setCRS(mapCrs);
        b.add("the_geom", Point.class); //$NON-NLS-1$
        for (String fieldName : GEOPAPARAZZI_NOTES_DESCRIPTIONFIELDS) {
            b.add(fieldName, String.class);
        }
        SimpleFeatureType featureType = b.buildFeatureType();
        MathTransform transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, mapCrs);
        pm.beginTask("Import notes...", IProgressMonitor.UNKNOWN); //$NON-NLS-1$
        DefaultFeatureCollection newCollection = new DefaultFeatureCollection();

        Statement statement = null;
        try {
            statement = connection.createStatement();
            statement.setQueryTimeout(30); // set timeout to 30 sec.

            ResultSet rs = statement.executeQuery("select lat, lon, altim, ts, text from notes"); //$NON-NLS-1$
            int i = 0;
            while (rs.next()) {

                double lat = rs.getDouble("lat"); //$NON-NLS-1$
                double lon = rs.getDouble("lon"); //$NON-NLS-1$
                double altim = rs.getDouble("altim"); //$NON-NLS-1$
                String dateTimeString = rs.getString("ts"); //$NON-NLS-1$
                String text = rs.getString("text"); //$NON-NLS-1$

                if (lat == 0 || lon == 0) {
                    continue;
                }

                // and then create the features
                Coordinate c = new Coordinate(lon, lat);
                Point point = gF.createPoint(c);
                Geometry reprojectPoint = JTS.transform(point, transform);

                SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
                Object[] values = new Object[] { reprojectPoint, text, dateTimeString,
                        String.valueOf(altim) };
                builder.addAll(values);
                SimpleFeature feature = builder.buildFeature(featureType.getTypeName() + "." + i++); //$NON-NLS-1$
                newCollection.add(feature);
                pm.worked(1);
            }
        } finally {
            pm.done();
            if (statement != null)
                statement.close();
        }

        ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
        Map<String, Serializable> params = new HashMap<>();
        params.put("url", outputShapeFile.toURI().toURL()); //$NON-NLS-1$
        params.put("create spatial index", Boolean.TRUE); //$NON-NLS-1$
        ShapefileDataStore dStore = (ShapefileDataStore) factory.createNewDataStore(params);
        dStore.createSchema(featureType);
        dStore.forceSchemaCRS(mapCrs);

        JGrassToolsPlugin.getDefault().writeToShapefile(dStore, newCollection);

        JGrassToolsPlugin.getDefault().addServiceToCatalogAndMap(outputShapeFile.getAbsolutePath(),
                true, true, new NullProgressMonitor());

    }

    private void gpsLogToShapefiles(Connection connection, File outputFolderFile,
            IProgressMonitor pm) throws Exception {
        File outputLinesShapeFile = new File(outputFolderFile, "gpslines.shp"); //$NON-NLS-1$

        Statement statement = connection.createStatement();
        statement.setQueryTimeout(30); // set timeout to 30 sec.

        List<GpsLog> logsList = new ArrayList<>();
        // first get the logs
        ResultSet rs = statement.executeQuery("select _id, startts, endts, text from gpslogs"); //$NON-NLS-1$
        while (rs.next()) {
            long id = rs.getLong("_id"); //$NON-NLS-1$

            String startDateTimeString = rs.getString("startts"); //$NON-NLS-1$
            String endDateTimeString = rs.getString("endts"); //$NON-NLS-1$
            String text = rs.getString("text"); //$NON-NLS-1$

            GpsLog log = new GpsLog();
            log.id = id;
            log.startTime = startDateTimeString;
            log.endTime = endDateTimeString;
            log.text = text;
            logsList.add(log);
        }

        statement.close();

        try {
            // then the log data
            for (GpsLog log : logsList) {
                long logId = log.id;
                String query = "select lat, lon, altim, ts from gpslog_data where logid = " + logId //$NON-NLS-1$
                        + " order by ts"; //$NON-NLS-1$

                Statement newStatement = connection.createStatement();
                newStatement.setQueryTimeout(30);
                ResultSet result = newStatement.executeQuery(query);

                while (result.next()) {
                    double lat = result.getDouble("lat"); //$NON-NLS-1$
                    double lon = result.getDouble("lon"); //$NON-NLS-1$
                    double altim = result.getDouble("altim"); //$NON-NLS-1$
                    String dateTimeString = result.getString("ts"); //$NON-NLS-1$

                    GpsPoint gPoint = new GpsPoint();
                    gPoint.lon = lon;
                    gPoint.lat = lat;
                    gPoint.altim = altim;
                    gPoint.utctime = dateTimeString;
                    log.points.add(gPoint);

                }

                newStatement.close();

            }
        } catch (Exception e) {
            e.printStackTrace();
            String message = "An error occurred while reading the gps logs."; //$NON-NLS-1$
            ExceptionDetailsDialog.openError(null, message, IStatus.ERROR,
                    JGrassToolsPlugin.PLUGIN_ID, e);
            return;
        }

        /**
         * Create the lines shapefile
         */
        SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
        b.setName("geopaparazzinotes"); //$NON-NLS-1$
        b.setCRS(mapCrs);
        b.add("the_geom", MultiLineString.class); //$NON-NLS-1$
        b.add("STARTDATE", String.class); //$NON-NLS-1$
        b.add("ENDDATE", String.class); //$NON-NLS-1$
        b.add("DESCR", String.class); //$NON-NLS-1$
        SimpleFeatureType featureType = b.buildFeatureType();

        try {
            MathTransform transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, mapCrs);
            pm.beginTask("Import gps to lines...", logsList.size()); //$NON-NLS-1$
            DefaultFeatureCollection newCollection = new DefaultFeatureCollection();
            int index = 0;
            for (GpsLog log : logsList) {
                List<GpsPoint> points = log.points;

                List<Coordinate> coordList = new ArrayList<>();
                String startDate = log.startTime;
                String endDate = log.endTime;
                for (GpsPoint gpsPoint : points) {
                    Coordinate c = new Coordinate(gpsPoint.lon, gpsPoint.lat);
                    coordList.add(c);
                }
                Coordinate[] coordArray = coordList.toArray(new Coordinate[coordList.size()]);
                if (coordArray.length < 2) {
                    continue;
                }
                LineString lineString = gF.createLineString(coordArray);
                LineString reprojectLineString = (LineString) JTS.transform(lineString, transform);
                MultiLineString multiLineString = gF
                        .createMultiLineString(new LineString[] { reprojectLineString });

                SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
                Object[] values = new Object[] { multiLineString, startDate, endDate, log.text };
                builder.addAll(values);
                SimpleFeature feature = builder
                        .buildFeature(featureType.getTypeName() + "." + index++); //$NON-NLS-1$

                newCollection.add(feature);
                pm.worked(1);
            }
            pm.done();

            ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
            Map<String, Serializable> params = new HashMap<>();
            params.put("url", outputLinesShapeFile.toURI().toURL()); //$NON-NLS-1$
            params.put("create spatial index", Boolean.TRUE); //$NON-NLS-1$
            ShapefileDataStore dStore = (ShapefileDataStore) factory.createNewDataStore(params);
            dStore.createSchema(featureType);
            dStore.forceSchemaCRS(mapCrs);

            JGrassToolsPlugin.getDefault().writeToShapefile(dStore, newCollection);

            JGrassToolsPlugin.getDefault().addServiceToCatalogAndMap(
                    outputLinesShapeFile.getAbsolutePath(), true, true, new NullProgressMonitor());

        } catch (Exception e1) {
            JGrassToolsPlugin.log(e1.getLocalizedMessage(), e1);
            e1.printStackTrace();
        }

        /**
         * Create the points shapefile
         */
        File outputPointsShapeFile = new File(outputFolderFile, "gpspoints.shp"); //$NON-NLS-1$

        b = new SimpleFeatureTypeBuilder();
        b.setName("geopaparazzinotes"); //$NON-NLS-1$
        b.setCRS(mapCrs);
        b.add("the_geom", Point.class); //$NON-NLS-1$
        b.add("ALTIMETRY", String.class); //$NON-NLS-1$
        b.add("DATE", String.class); //$NON-NLS-1$
        featureType = b.buildFeatureType();

        try {
            MathTransform transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, mapCrs);

            pm.beginTask("Import gps to points...", logsList.size()); //$NON-NLS-1$
            DefaultFeatureCollection newCollection = new DefaultFeatureCollection();
            int index = 0;
            for (GpsLog log : logsList) {
                List<GpsPoint> gpsPointList = log.points;
                for (GpsPoint gpsPoint : gpsPointList) {
                    Coordinate c = new Coordinate(gpsPoint.lon, gpsPoint.lat);
                    Point point = gF.createPoint(c);

                    Point reprojectPoint = (Point) JTS.transform(point, transform);
                    Object[] values = new Object[] { reprojectPoint, String.valueOf(gpsPoint.altim),
                            gpsPoint.utctime };

                    SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
                    builder.addAll(values);
                    SimpleFeature feature = builder
                            .buildFeature(featureType.getTypeName() + "." + index++); //$NON-NLS-1$
                    newCollection.add(feature);
                }
                pm.worked(1);
            }
            pm.done();

            ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
            Map<String, Serializable> params = new HashMap<>();
            params.put("url", outputPointsShapeFile.toURI().toURL()); //$NON-NLS-1$
            params.put("create spatial index", Boolean.TRUE); //$NON-NLS-1$
            ShapefileDataStore dStore = (ShapefileDataStore) factory.createNewDataStore(params);
            dStore.createSchema(featureType);
            dStore.forceSchemaCRS(mapCrs);

            JGrassToolsPlugin.getDefault().writeToShapefile(dStore, newCollection);

            JGrassToolsPlugin.getDefault().addServiceToCatalogAndMap(
                    outputPointsShapeFile.getAbsolutePath(), true, true, new NullProgressMonitor());

        } catch (Exception e1) {
            JGrassToolsPlugin.log(e1.getLocalizedMessage(), e1);
            e1.printStackTrace();
        }
    }

    private void mediaToShapeFile(File geopapFolderFile, File outputFolderFile, IProgressMonitor pm)
            throws Exception {
        File folder = new File(geopapFolderFile, "media"); //$NON-NLS-1$
        if (!folder.exists()) {
            // try to see if it is an old version of geopaparazzi
            folder = new File(geopapFolderFile, "pictures"); //$NON-NLS-1$
            if (!folder.exists()) {
                // ignoring non existing things
                return;
            }
        }

        // create destination folder
        String imageFolderName = "media"; //$NON-NLS-1$

        File[] listFiles = folder.listFiles();
        List<String> nonTakenFilesList = new ArrayList<>();

        pm.beginTask("Importing media...", listFiles.length); //$NON-NLS-1$
        try {

            /**
             * Create the points shapefile
             */
            File outputPointsShapeFile = new File(outputFolderFile, "mediapoints.shp"); //$NON-NLS-1$

            SimpleFeatureTypeBuilder b = new SimpleFeatureTypeBuilder();
            b.setName("geopaparazzinotes"); //$NON-NLS-1$
            b.setCRS(mapCrs);
            b.add("the_geom", Point.class); //$NON-NLS-1$
            b.add("ALTIMETRY", String.class); //$NON-NLS-1$
            b.add("DATE", String.class); //$NON-NLS-1$
            b.add("AZIMUTH", Double.class); //$NON-NLS-1$
            b.add("IMAGE", String.class); //$NON-NLS-1$
            SimpleFeatureType featureType = b.buildFeatureType();

            MathTransform transform = CRS.findMathTransform(DefaultGeographicCRS.WGS84, mapCrs);

            DefaultFeatureCollection newCollection = new DefaultFeatureCollection();
            for (File imageFile : listFiles) {
                String name = imageFile.getName();
                if (name.endsWith("jpg") || imageFile.getName().endsWith("JPG") //$NON-NLS-1$ //$NON-NLS-2$
                        || imageFile.getName().endsWith("png") //$NON-NLS-1$
                        || imageFile.getName().endsWith("PNG") //$NON-NLS-1$
                        || imageFile.getName().endsWith("3gp")) { //$NON-NLS-1$

                    String[] nameSplit = name.split("[_\\|.]"); //$NON-NLS-1$
                    String dateString = nameSplit[1];
                    String timeString = nameSplit[2];

                    Properties locationProperties = new Properties();
                    String mediaPath = imageFile.getAbsolutePath();
                    int lastDot = mediaPath.lastIndexOf("."); //$NON-NLS-1$
                    String nameNoExt = mediaPath.substring(0, lastDot);
                    String infoPath = nameNoExt + ".properties"; //$NON-NLS-1$
                    File infoFile = new File(infoPath);
                    if (!infoFile.exists()) {
                        nonTakenFilesList.add(mediaPath);
                        continue;
                    }
                    locationProperties.load(new FileInputStream(infoFile));
                    String azimuthString = locationProperties.getProperty("azimuth"); //$NON-NLS-1$
                    String latString = locationProperties.getProperty("latitude"); //$NON-NLS-1$
                    String lonString = locationProperties.getProperty("longitude"); //$NON-NLS-1$
                    String altimString = locationProperties.getProperty("altim"); //$NON-NLS-1$

                    Double azimuth = -9999.0;
                    if (azimuthString != null)
                        azimuth = Double.parseDouble(azimuthString);
                    double lat = 0.0;
                    double lon = 0.0;
                    if (latString.contains("/")) { //$NON-NLS-1$
                        // this is an exif string
                        lat = exifFormat2degreeDecimal(latString);
                        lon = exifFormat2degreeDecimal(lonString);
                    } else {
                        lat = Double.parseDouble(latString);
                        lon = Double.parseDouble(lonString);
                    }
                    double altim = Double.parseDouble(altimString);

                    Coordinate c = new Coordinate(lon, lat);
                    Point point = gF.createPoint(c);

                    String imageRelativePath = imageFolderName + "/" + imageFile.getName(); //$NON-NLS-1$
                    File newImageFile = new File(outputFolderFile, imageRelativePath);
                    FileUtils.copyFile(imageFile, newImageFile);

                    Point reprojectPoint = (Point) JTS.transform(point, transform);
                    String dateTime = dateString + timeString;
                    Object[] values = new Object[] { reprojectPoint, String.valueOf(altim),
                            dateTime, azimuth, imageRelativePath };

                    SimpleFeatureBuilder builder = new SimpleFeatureBuilder(featureType);
                    builder.addAll(values);
                    SimpleFeature feature = builder.buildFeature(null);
                    newCollection.add(feature);
                }
                pm.worked(1);
            }

            ShapefileDataStoreFactory factory = new ShapefileDataStoreFactory();
            Map<String, Serializable> params = new HashMap<>();
            params.put("url", outputPointsShapeFile.toURI().toURL()); //$NON-NLS-1$
            params.put("create spatial index", Boolean.TRUE); //$NON-NLS-1$
            ShapefileDataStore dStore = (ShapefileDataStore) factory.createNewDataStore(params);
            dStore.createSchema(featureType);
            dStore.forceSchemaCRS(mapCrs);

            JGrassToolsPlugin.getDefault().writeToShapefile(dStore, newCollection);

            JGrassToolsPlugin.getDefault().addServiceToCatalogAndMap(
                    outputPointsShapeFile.getAbsolutePath(), true, true, new NullProgressMonitor());
        } finally {
            pm.done();
        }

        if (nonTakenFilesList.size() > 0) {
            final StringBuilder sB = new StringBuilder();
            sB.append("For the following media no *.properties file could be found:\n"); //$NON-NLS-1$
            for (String p : nonTakenFilesList) {
                sB.append(p).append("\n"); //$NON-NLS-1$
            }

            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
                    MessageDialog.openWarning(shell, "Warning", sB.toString()); //$NON-NLS-1$
                }
            });
        } else {
            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    Shell shell = PlatformUI.getWorkbench().getDisplay().getActiveShell();
                    MessageDialog.openInformation(shell, "Info", //$NON-NLS-1$
                            "All media were successfully imported."); //$NON-NLS-1$
                }
            });
        }

    }

    private static class GpsPoint {
        public double lat;

        public double lon;

        public double altim;

        public String utctime;
    }

    private static class GpsLog {
        public long id;

        public String startTime;

        public String endTime;

        public String text;

        public List<GpsPoint> points = new ArrayList<>();

    }

    /**
     * Convert decimal degrees to exif format.
     *
     * @param decimalDegree the angle in decimal format.
     * @return the exif format string.
     */
    public static String degreeDecimal2ExifFormat(double decimalDegree) {
        StringBuilder sb = new StringBuilder();
        sb.append((int) decimalDegree);
        sb.append("/1,"); //$NON-NLS-1$
        decimalDegree = (decimalDegree - (int) decimalDegree) * 60;
        sb.append((int) decimalDegree);
        sb.append("/1,"); //$NON-NLS-1$
        decimalDegree = (decimalDegree - (int) decimalDegree) * 60000;
        sb.append((int) decimalDegree);
        sb.append("/1000"); //$NON-NLS-1$
        return sb.toString();
    }

    /**
     * Convert exif format to decimal degree.
     *
     * @param exifFormat the exif string of the gps position.
     * @return the decimal degree.
     */
    public static double exifFormat2degreeDecimal(String exifFormat) {
        // latitude=44/1,10/1,28110/1000
        String[] exifSplit = exifFormat.trim().split(","); //$NON-NLS-1$

        String[] value = exifSplit[0].split("/"); //$NON-NLS-1$

        double tmp1 = Double.parseDouble(value[0]);
        double tmp2 = Double.parseDouble(value[1]);
        double degree = tmp1 / tmp2;

        value = exifSplit[1].split("/"); //$NON-NLS-1$
        tmp1 = Double.parseDouble(value[0]);
        tmp2 = Double.parseDouble(value[1]);
        double minutes = tmp1 / tmp2;

        value = exifSplit[2].split("/"); //$NON-NLS-1$
        tmp1 = Double.parseDouble(value[0]);
        tmp2 = Double.parseDouble(value[1]);
        double seconds = tmp1 / tmp2;

        double result = degree + (minutes / 60.0) + (seconds / 3600.0);
        return result;
    }

}
