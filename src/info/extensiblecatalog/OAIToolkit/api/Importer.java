/**
  * Copyright (c) 2009 University of Rochester
  *
  * This program is free software; you can redistribute it and/or modify it under the terms of the MIT/X11 license. The text of the
  * license can be found at http://www.opensource.org/licenses/mit-license.php and copy of the license can be found on the project
  * website http://www.extensiblecatalog.org/.
  *
  */

package info.extensiblecatalog.OAIToolkit.api;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Date;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import org.apache.log4j.Logger;
import org.marc4j.MarcException;
import org.marc4j.MarcReader;
import org.marc4j.MarcXmlReader;
import org.marc4j.MarcXmlWriter;
import org.marc4j.marc.Record;
import org.xml.sax.SAXParseException;

import info.extensiblecatalog.OAIToolkit.importer.CLIProcessor;
import info.extensiblecatalog.OAIToolkit.importer.Converter;
import info.extensiblecatalog.OAIToolkit.importer.DirectoryNameGiver;
import info.extensiblecatalog.OAIToolkit.importer.FileNameComparator;
import info.extensiblecatalog.OAIToolkit.importer.ImporterConfiguration;
import info.extensiblecatalog.OAIToolkit.importer.ImporterConstants;
import info.extensiblecatalog.OAIToolkit.importer.MARCFileNameFilter;
import info.extensiblecatalog.OAIToolkit.importer.MARCRecordWrapper;
import info.extensiblecatalog.OAIToolkit.importer.Modifier;
import info.extensiblecatalog.OAIToolkit.importer.XMLFileNameFilter;
import info.extensiblecatalog.OAIToolkit.importer.ImporterConstants.ImportType;
import info.extensiblecatalog.OAIToolkit.importer.importers.IImporter;
import info.extensiblecatalog.OAIToolkit.importer.importers.LuceneImporter;
import info.extensiblecatalog.OAIToolkit.importer.importers.MixedImporter;
import info.extensiblecatalog.OAIToolkit.importer.importers.MysqlImporter;
import info.extensiblecatalog.OAIToolkit.importer.statistics.ConversionStatistics;
import info.extensiblecatalog.OAIToolkit.importer.statistics.LoadStatistics;
import info.extensiblecatalog.OAIToolkit.importer.statistics.ModificationStatistics;
import info.extensiblecatalog.OAIToolkit.oai.StorageTypes;
import info.extensiblecatalog.OAIToolkit.utils.ApplInfo;
import info.extensiblecatalog.OAIToolkit.utils.ExceptionPrinter;
import info.extensiblecatalog.OAIToolkit.utils.Logging;
import info.extensiblecatalog.OAIToolkit.utils.XMLUtil;

/**
 * Public interface for converting and importing MARC records to
 * OAI database.
 * @author Peter Kiraly
 */
public class Importer {

	/** The logger object */
        private static String library_convertlog = "librarian_convert";
	private static String library_loadlog = "librarian_load";
        private static String programmer_log = "programmer";


	//private static final Logger Log = Logging.getLogger();
        private static final Logger libconvertlog = Logging.getLogger(library_convertlog);
        private static final Logger libloadlog = Logging.getLogger(library_loadlog);
        private static final Logger prglog = Logging.getLogger(programmer_log);

	public static final String VERSION = "0.5.1";

	private IImporter recordImporter;

	/** The configurations came from command line arguments */
	public ImporterConfiguration configuration = new ImporterConfiguration();

	private DirectoryNameGiver dirNameGiver;

	/** statistics about conversion step */
	private ConversionStatistics conversionStatistics;

	/** statistics about modification step */
	private ModificationStatistics modificationStatistics;

	/** statistics about load step */
	private LoadStatistics importStatistics;

	public Importer() {}

	/** Initialize the record handlers */
	private void init() throws Exception {
		String root = new File(".").getAbsoluteFile().getParent();
		ApplInfo.init(root, configuration.getLogDir());
		dirNameGiver = new DirectoryNameGiver(configuration);
	}

    public void execute() {
    	if(!configuration.isNeedConvert()
    		&& !configuration.isNeedModify()
    		&& !configuration.isNeedLoad())
    	{
    		CLIProcessor.help();
    	}
    	prglog.info("[PRG] " + dirNameGiver.getInfo());
    	if(configuration.isNeedConvert()) {
    		conversionStatistics = null;
    		convert();
    	}
    	if(configuration.isNeedModify() && !configuration.isProductionMode()) {
    		modificationStatistics = null;
   			modify();
    	}
    	if(configuration.isNeedLoad()) {
    		if(!configuration.isProductionMode()
    			|| (configuration.isProductionMode()
    				&& !configuration.isNeedConvert()))
    		{
    			importStatistics = null;
    			load();
    		}
    	}
    	if(configuration.isNeedLogDetail()) {
    		prglog.info("[PRG] Import finished");
                libloadlog.info("[LIB] Import finished");
    	}
    }

	private void convert(){
		if(!configuration.checkSourceDir()
			|| !configuration.checkDestinationDir()
			|| !configuration.checkDestinationXmlDir()
			|| !configuration.errorDir()
			|| !configuration.errorXmlDir()) {
			return;
		}

		// if there is a load command, change the xml destination dir to
		// temporary xml dir and store the original destination xml dir
		//String originalDestinationXmlDir = null;
		if(configuration.isNeedLoad()) {
			//originalDestinationXmlDir = configuration.getDestinationXmlDir();
			//Log.info("originalDestinationXmlDir: " + originalDestinationXmlDir);
			//File original = new File(originalDestinationXmlDir);
			//Log.info(original.getAbsolutePath());
			//File parent = original.getParentFile();
			//File tempXml = new File(parent, "tempXml");
			File tempXml = dirNameGiver.getConvertTarget();
			if(!tempXml.exists()) {
				boolean created = tempXml.mkdir();
				if(!created) {
					prglog.error("[PRG] Unable to create temporary dir: " + tempXml);
				}
			}
			//configuration.setDestinationXmlDir(tempXml.getName());
		}


		if(configuration.isNeedLogDetail()) {
                        libconvertlog.info(" *********** START OF CONVERT PROCESS ************ \n");
			prglog.info("[PRG] Start conversion from MARC files " +
					"at " + dirNameGiver.getConvertSource()
					+ " to MARCXML files at "
					+ dirNameGiver.getConvertTarget());
                        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                        Date convStartDate = new Date();
                        libconvertlog.info("[LIB] Conversion started at " + dateFormat.format(convStartDate));
                        libconvertlog.info("[LIB] Start conversion from MARC files " +
					"at " + dirNameGiver.getConvertSource()
					+ " to MARCXML files at "
					+ dirNameGiver.getConvertTarget() + "\n\n");
		}

		Converter converter = new Converter();
		if(null != configuration.getMarcEncoding()) {
			converter.setEncoding(configuration.getMarcEncoding());
		}

		if(null != configuration.getCharConversion()) {
			converter.setConvertEncoding(configuration.getCharConversion());
		}


		if(configuration.isNeedModify() && configuration.isProductionMode()) {
			converter.setModifier(new Modifier(configuration));
		}

		if(configuration.isNeedLoad() && configuration.isProductionMode()) {
			initRecordImporter();
			converter.setRecordImporter(recordImporter);
			importStatistics = new LoadStatistics();
		}

		converter.setSplitSize(configuration.getSplitSize());
		converter.setDoIndentXml(configuration.isDoIndentXml());
		converter.setErrorDir(dirNameGiver.getConvertError().getAbsolutePath());
		converter.setCreateXml11(configuration.isCreateXml11());
                converter.setTranslateLeaderBadCharsToZero(configuration.isTranslateLeaderBadCharsToZero());
                converter.setTranslateNonleaderBadCharsToSpaces(configuration.isTranslateNonleaderBadCharsToSpaces());
		converter.setIgnoreRepositoryCode(configuration.doesIgnoreRepositoryCode());
		converter.setDefaultRepositoryCode(configuration.getDefaultRepositoryCode());

		prglog.info("[PRG] " + converter.getSettings());

		File fSourceDir = dirNameGiver.getConvertSource();
		File[] files = fSourceDir.listFiles(new MARCFileNameFilter());
		if(0 == files.length) {
			prglog.warn("[PRG] There's no MARC file in the source directory: "
					+ configuration.getSourceDir());
		}
		Arrays.sort(files, new FileNameComparator());

		conversionStatistics = new ConversionStatistics();
		ConversionStatistics fileStatistics;

		for(File marcFile : files) {
			File xmlFile = new File(configuration.getDestinationXmlDir(),
					marcFile.getName().replaceAll(".mrc$", ".xml"));
			try {
				// setting the XML file
				if(configuration.isNeedLogDetail()){
					prglog.info("[PRG] Converting " + marcFile.getName()
							+ " to " + xmlFile.getName());
                                        libconvertlog.info("[LIB] Converting " + marcFile.getName()
							+ " to " + xmlFile.getName() + "\n\n");
                                        }

				// CONVERT !!!!
				fileStatistics = converter.convert(marcFile, xmlFile);

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] " + fileStatistics.toString(marcFile.getName()));
					if(importStatistics != null) {
						prglog.info("[PRG] " + converter.getLoadStatistics().toString(
							marcFile.getName()));
					}
				}

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] Moving " + marcFile.getName() + " to "
							+ dirNameGiver.getConvertDestination());
				}
				// setting the destination file
				File successFile = new File(dirNameGiver.getConvertDestination(),
						marcFile.getName());

				// delete if exists (otherwise the moving won't success)
				if(successFile.exists()) {
					boolean deleted = successFile.delete();
					prglog.info("[PRG] Delete " + successFile + " - " + deleted);
				}
				// remove
				boolean remove = marcFile.renameTo(successFile);
				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] remove marc file (" + marcFile.getName() + ") to "
							+ dirNameGiver.getConvertDestination()
							+ ": " + remove);
				}

				conversionStatistics.add(fileStatistics);
				if(importStatistics != null) {
					importStatistics.add(converter.getLoadStatistics());
				}

			} catch(Exception e){
				if(e instanceof MarcException) {
					prglog.error("[PRG] " + e.getMessage()
					+ ". The last successfully read record's Control Number is "
					+ converter.getControlNumberOfLastReadRecord()
					+ ". The error may be in the next record.");
       				} else {
					e.printStackTrace();
					prglog.error("[PRG] " + e);
				}
				// copy marcFile -> errorDir
				File errorFile = new File(configuration.getErrorDir(), marcFile.getName());
				if(errorFile.exists()) {
					boolean deleted = errorFile.delete();
					if(deleted) {
						prglog.info("[PRG] Delete " + errorFile + ".");
					} else {
						prglog.error("[PRG] Unable to delete " + errorFile + ".");
					}
				}
				boolean remove = marcFile.renameTo(errorFile);
				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] remove MARC to error directory: " + remove);
				}

				if(xmlFile.exists()){
					File xmlErrorFile = new File(configuration.getErrorXmlDir(), xmlFile.getName());
					if(xmlErrorFile.exists()) {
						boolean deleted = xmlErrorFile.delete();
						if(deleted) {
							prglog.info("[PRG] Delete " + xmlErrorFile);
						} else {
							prglog.error("[PRG] Unable to delete " + xmlErrorFile);
						}
					}
					remove = xmlFile.renameTo(xmlErrorFile);
					if(configuration.isNeedLogDetail()) {
						prglog.info("[PRG] remove XML to error_xml directory: " + remove);
					}
				}
			}
		}

		if(configuration.isNeedLogDetail()) {
                        DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                        Date convEndDate = new Date();
                        libconvertlog.info("[LIB] Conversion completed at " + dateFormat.format(convEndDate));
			prglog.info("[PRG] Conversion statistics summary: "
					+ conversionStatistics.toString());
                        libconvertlog.info("[LIB] Conversion statistics summary: "
					+ conversionStatistics.toString() + "\n");
                        libconvertlog.info(" *********** END OF CONVERT PROCESS ************ \n");
			if(importStatistics != null) {
				prglog.info("[PRG] Load statistics summary: "
						+ importStatistics.toString());
                                libloadlog.info("[LIB] Load statistics summary: "
						+ importStatistics.toString());
			}
		}

		if(recordImporter != null) {
			recordImporter.optimize();
		}

		// if there is a load command, change the source dir to
		// temporary xml dir and restore the original destination xml dir
		/*
		if(configuration.isNeedLoad() && null != originalDestinationXmlDir) {
			configuration.setSourceDir(configuration.getDestinationXmlDir());
			configuration.setDestinationXmlDir(originalDestinationXmlDir);
		}
		*/
	}

	private void modify(){
                prglog.info(" *********** START OF MODIFY PROCESS ************ \n");
		prglog.info("[PRG] Start modifying of MARCXML files from " + dirNameGiver
				.getModifySource() + " to " + dirNameGiver
				.getModifyTarget());
                DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                Date modifyStartDate = new Date();
                prglog.info("[LIB] Modify started at " + dateFormat.format(modifyStartDate));
                prglog.info("[LIB] Start modifying of MARCXML files from " + dirNameGiver
				.getModifySource() + " to " + dirNameGiver
				.getModifyTarget());
		File[] files = dirNameGiver.getModifySource().listFiles(
				new XMLFileNameFilter());
		if(0 == files.length) {
			prglog.warn("[PRG] There's no XML file in the source directory.");
		}
		Arrays.sort(files, new FileNameComparator());
		Modifier modifier = new Modifier(configuration);
		int counter;

		modificationStatistics = new ModificationStatistics();
		ModificationStatistics fileStatistics = null;

		for(File xmlFile : files) {
			InputStream in = null;
			OutputStream out = null;
			MarcReader marcReader = null;
			try {
				if(!checkXml(xmlFile)) {
					continue;
				}

				fileStatistics = new ModificationStatistics();
				System.setProperty("file.encoding", "UTF-8");
				long fileSize = xmlFile.length();
				in = new FileInputStream(xmlFile);
				marcReader = new MarcXmlReader(in);
				out = new FileOutputStream(new File(
						dirNameGiver.getModifyTarget(), xmlFile.getName()));
				out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".getBytes());
				out.write("<collection xmlns=\"http://www.loc.gov/MARC21/slim\">\n".getBytes());

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] Modifying records...");
				}
				counter = 0;
				int prevPercent = 0;
				/** the percent of imported records in the size of file */
				int percent;
				while (marcReader.hasNext()) {
					String xml = modifier.modifyRecord(marcReader.next(), configuration.isFileOfDeletedRecords());
					out.write(xml.getBytes());
					fileStatistics.addTransformed();

					counter++;
					if(configuration.isNeedLogDetail() && (0 == counter % 100)){
						System.out.print('.');
						if(marcReader.hasNext()) {
							try {
								if(in != null && in.available() != 0) {
									percent = (int)((fileSize - in.available())
										* 100 / fileSize);
									if((0 == percent % 10)
											&& percent != prevPercent)
									{
										System.out.println(" (" + percent + "%)");
										prevPercent = percent;
									}
								}
							} catch(IOException e) {
								e.printStackTrace();
								prglog.error("[PRG] " + ExceptionPrinter.getStack(e));
							}
						}
						//System.gc();
					}

					/*
					List<ImportType> typeList = recordImporter.importRecord(record);
					fileStatistics.add(typeList);
					if(typeList.contains(ImportType.INVALID)) {
						if(null == badRecordWriter){
							badRecordWriter = new MarcXmlWriter(
								new FileOutputStream(
									new File(
										configuration.getErrorXmlDir(),
										"error_records_in_" + xmlFile.getName()
									)),
								"UTF8", // encoding
								true//, // indent
								);//configuration.isCreateXml11()); // xml 1.0
						}
						badRecordWriter.write(record);
					}
					*/
				}
				out.write("</collection>\n".getBytes());
				out.close();

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] Modify statistics for " + xmlFile.getName()
							+ ": " + fileStatistics.toString());
				}
				modificationStatistics.add(fileStatistics);
			} catch(FileNotFoundException e) {
				prglog.error("[PRG] " + ExceptionPrinter.getStack(e));
			} catch(IOException e) {
				prglog.error("[PRG] " + ExceptionPrinter.getStack(e));
			} finally {
				try {
					if(in != null)
						in.close();
				} catch(IOException e) {
					prglog.error("[PRG] " + ExceptionPrinter.getStack(e));
				}
				try {
					if(out != null)
						out.close();
				} catch(IOException e) {
					prglog.error("[PRG] " + ExceptionPrinter.getStack(e));
				}
			}
		} // for File...
		if(configuration.isNeedLogDetail()) {
			prglog.info("[PRG] Modify statistics summary: "
					+ modificationStatistics.toString());
                        Date modifyEndDate = new Date();
                        prglog.info("[LIB] Modify completed at " + dateFormat.format(modifyEndDate));
                        prglog.info("[LIB] Modify statistics summary: "
					+ modificationStatistics.toString() + "\n");
                        prglog.info(" *********** END OF MODIFY PROCESS ************ \n");
		}
	} // modify

	private void load(){
		if(!configuration.checkSourceDir()
			|| !configuration.checkDestinationXmlDir()
			|| !configuration.errorXmlDir()) {
			return;
		}
                libloadlog.info(" *********** START OF LOAD PROCESS ************ \n");
		prglog.info("[PRG] Start loading of MARCXML files from "
				+ dirNameGiver.getLoadSource());
                DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");
                Date loadStartDate = new Date();
                libloadlog.info("[LIB] Load started at " + dateFormat.format(loadStartDate));
                libloadlog.info("[LIB] Start loading of MARCXML files from "
				+ dirNameGiver.getLoadSource() + "\n\n");

		File[] files = dirNameGiver.getLoadSource().listFiles(
				new XMLFileNameFilter());
		if(0 == files.length) {
			prglog.warn("[PRG] There's no XML file in the source directory.");
		}
		Arrays.sort(files, new FileNameComparator());

		prglog.info("[PRG] Storage type: " + configuration.getStorageType());


		initRecordImporter();
		Modifier modifier = null;
		if(configuration.isNeedModify() && configuration.isProductionMode()) {
			modifier = new Modifier(configuration);
		}

		int counter = 0;
		MarcXmlWriter badRecordWriter = null;
		importStatistics = new LoadStatistics();
		LoadStatistics fileStatistics = null;

		for(File xmlFile : files) {

			recordImporter.setCurrentFile(xmlFile.getName());
			fileStatistics = new LoadStatistics();

			badRecordWriter = null;
			try {
				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] loading " + xmlFile.getName());
				}

				if(!checkXml(xmlFile)) {
					continue;
				}

				System.setProperty("file.encoding", "UTF-8");
				long fileSize = xmlFile.length();
				InputStream in = new FileInputStream(xmlFile);
				MarcReader marcReader = new MarcXmlReader(in);

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] Importing records...");
				}
				counter = 0;

				int prevPercent = 0;
				Record record;

				/** the percent of imported records in the size of file */
				int percent;
				while (marcReader.hasNext()) {
					record = marcReader.next();

                    if(modifier != null) {
						String xml = modifier.modifyRecord(record, configuration.isFileOfDeletedRecords());
						record = MARCRecordWrapper.MARCXML2Record(xml);
					}
                    List<ImportType> typeList = recordImporter.importRecord(record, configuration.isFileOfDeletedRecords());
					fileStatistics.add(typeList);
					fileStatistics.add(recordImporter.getCheckTime(),
							recordImporter.getInsertTime());
					if(typeList.contains(ImportType.INVALID)) {
						recordImporter.writeBadRecord(record);
					}
					counter++;
					if(configuration.isNeedLogDetail() && (0 == counter % 100)) {
						System.out.print('.');
						if(marcReader.hasNext()) {
							try {
								percent = (int)((fileSize - in.available()) * 100 / fileSize);
								if((0 == percent % 10) && percent != prevPercent) {
									System.out.println(" (" + percent + "%)");
									prevPercent = percent;
								}
							} catch(Exception e) {

							}
						}
						//System.gc();
					}
				}
				if(configuration.isNeedLogDetail() && (counter > 100)) {
					System.out.println();
				}
				in.close();

				if(configuration.isNeedLogDetail()) {
					prglog.info("[PRG] " + fileStatistics.toString(xmlFile.getName()));
                                        libloadlog.info("[LIB] " + fileStatistics.toString(xmlFile.getName()) + "\n\n");
				}
				importStatistics.add(fileStatistics);

				// move file to destination xml directory
				if(configuration.isDoDeleteTemporaryFiles()) {
					prglog.info("[PRG] Delete " + xmlFile);
					boolean remove = xmlFile.delete();
					if(configuration.isNeedLogDetail()) {
						prglog.info("[PRG] Deleting XML file (" + xmlFile.getName()
							+ ") " + remove);
					}
				} else if(!dirNameGiver.getLoadSource().equals(
						dirNameGiver.getLoadDestination())) {
					File destXmlFile = new File(
						dirNameGiver.getLoadDestination(),
						xmlFile.getName());
					if(destXmlFile.exists()) {
						boolean deleted = destXmlFile.delete();
						if(deleted) {
							prglog.info("[PRG] Delete " + destXmlFile);
						} else {
							prglog.error("[PRG] Unable to delete " + destXmlFile);
						}
					}
					boolean remove = xmlFile.renameTo(destXmlFile);
					if(configuration.isNeedLogDetail()) {
						prglog.info("[PRG] Move XML file (" + xmlFile.getName()
							+ ") to destination_xml directory ("
							+ destXmlFile.getAbsolutePath() + "): " + remove);
					}
				}
				recordImporter.commit();

			} catch(IOException e) {
				e.printStackTrace();
				prglog.error("[PRG] [IOException] " + e.getMessage()
						+ " " + xmlFile.getName()
						+ " lastRecordToImport: "
						+ recordImporter.getLastRecordToImport());
                        } catch(MarcException e) {
                                e.printStackTrace();
				prglog.error("[PRG] [MarcException] " + e.getMessage()
						+ " " + xmlFile.getName()
						+ " lastRecordToImport: "
						+ recordImporter.getLastRecordToImport());
                        } catch(RuntimeException e) {
				e.printStackTrace();
				prglog.error("[PRG] [RuntimeException] " + e.getMessage()
						+ " " + xmlFile.getName()
						+ " lastRecordToImport: "
						+ recordImporter.getLastRecordToImport());
                        } catch(Exception e) {
                                e.printStackTrace();
				prglog.error("[PRG] " + e.getMessage()
						+ " " + xmlFile.getName()
						+ " lastRecordToImport: "
						+ recordImporter.getLastRecordToImport());
                        } finally {
				if(null != badRecordWriter) {
					try {
						badRecordWriter.close();
						badRecordWriter = null;
					} catch(Exception e) {
						e.printStackTrace();
						prglog.error("[PRG] Error in closing bad records file "
								+ e.getMessage()
								+ " error_records_in_" + xmlFile.getName());
					}
				}
			}
		}

		if(configuration.isNeedLogDetail()) {
			prglog.info("[PRG] Import statistics summary: " + importStatistics.toString());
                        Date loadEndDate = new Date();
                        libloadlog.info("[LIB] Load completed at " + dateFormat.format(loadEndDate));
                        libloadlog.info(" *********** END OF LOAD PROCESS ************ \n");
                        libloadlog.info("[LIB] Import statistics summary: " + importStatistics.toString() + "\n");
		}

		// remove temporary xml directory
		if(configuration.isNeedConvert()) {
			int numOfFiles = dirNameGiver.getLoadSource().listFiles().length;
			if(0 == numOfFiles) {
				boolean isDeleted = dirNameGiver.getLoadSource().delete();
				if(!isDeleted) {
					prglog.error("[PRG] Unable to delete " + dirNameGiver.getLoadSource());
				}
			} else {
				prglog.error("[PRG] Can't delete the temporary xml directory, because" +
						" there exist " + numOfFiles + " files in it.");
			}
		}

		if(configuration.isNeedLogDetail()) {
			prglog.info("[PRG] optimizing database...");
		}

		recordImporter.optimize();
		if(configuration.isNeedLogDetail()) {
			prglog.info("[PRG] Import done");

		}
	}

	private boolean checkXml(File xmlFile) {
		try {
			//boolean isValid = XMLUtil.validate(xmlFile, schemaFile, configuration.isNeedLogDetail());
			boolean isWellFormed = XMLUtil.isWellFormed2(xmlFile);
			if(!isWellFormed) {
				return false;
			}
			if(configuration.isNeedLogDetail()) {
				prglog.info("[PRG] This is a valid MARCXML file.");
			}
			return true;
		} catch(Exception e){
			String error = "The XML file (" + xmlFile.getName() + ") "
				+ "isn't well formed. Please correct the errors and "
				+ "load again. Error description: " + e.getMessage();
			if(e instanceof SAXParseException) {
				error += " Location: "
					+ ((SAXParseException)e).getLineNumber()
					+ ":" + ((SAXParseException)e).getColumnNumber() + ".";
			}
			libloadlog.error("[LIB] " + error);
			File xmlErrorFile = new File(dirNameGiver.getLoadError(),
					xmlFile.getName());
			if(xmlErrorFile.exists()) {
				boolean deleted = xmlErrorFile.delete();
				prglog.info("[PRG] Delete " + xmlErrorFile + " - " + deleted);
			}
			boolean remove = xmlFile.renameTo(xmlErrorFile);
			if(configuration.isNeedLogDetail()) {
				prglog.info("[PRG] Remove XML file (" + xmlFile.getName()
						+ ") to error_xml directory: " + remove);
			}
			importStatistics.add(ImportType.INVALID_FILES);
			return false;
		}
	}

	private void initRecordImporter() {

		String schemaFile = ImporterConstants.MARC_SCHEMA_URL;
		if (null != configuration.getMarcSchema()) {
			File schema = new File(configuration.getMarcSchema());
			if (schema.exists()) {
				schemaFile = configuration.getMarcSchema();
			} else {
				prglog.warn("[PRG] The schema file in the marc_schema option"
						+ "doesn't exist. The OAIToolkit use the LoC's schema "
						+ "instead: " + ImporterConstants.MARC_SCHEMA_URL);
			}
		}

        if (configuration.isFileOfDeletedRecords()) {

        }
		if(configuration.getStorageType().equals(StorageTypes.MIXED)
			&& configuration.getLuceneIndex() != null)
		{
			prglog.info("[PRG] LuceneIndex: " + configuration.getLuceneIndex());
			recordImporter = new MixedImporter(schemaFile,
						configuration.getLuceneIndex());
		}
		// if we use Lucene, use LuceneImporter
		else if(configuration.getStorageType().equals(StorageTypes.LUCENE)
				&& configuration.getLuceneIndex() != null)
		{
			prglog.info("[PRG] LuceneIndex: " + configuration.getLuceneIndex());
			recordImporter = new LuceneImporter(schemaFile,
						configuration.getLuceneIndex());
		} else {
			// else use the MySQL based RecordImporter
			recordImporter = new MysqlImporter(schemaFile);
		}

		recordImporter.setDoIndentXml(configuration.isDoIndentXml());
		recordImporter.setCreateXml11(configuration.isCreateXml11());
		recordImporter.setErrorXmlDir(configuration.getErrorXmlDir());
	}

	public static void main(String[] args) {
		Importer importer = new Importer();
		try {
			CLIProcessor.process(args, importer);
			importer.init();
			prglog.info("[PRG] Importer v" + VERSION);
			prglog.info("[PRG] " + importer.configuration.getParams());
			importer.execute();
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

	public LoadStatistics getImportStatistics() {
		return importStatistics;
	}

	public ConversionStatistics getConversionStatistics() {
		return conversionStatistics;
	}
}
