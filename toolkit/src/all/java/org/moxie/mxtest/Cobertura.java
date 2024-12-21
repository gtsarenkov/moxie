/*
 * Copyright 2012 James Moger
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.moxie.mxtest;

import net.sourceforge.cobertura.ant.InstrumentTask;

import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.Path;
import org.apache.tools.ant.types.Reference;
import org.moxie.Toolkit.Key;
import org.moxie.ant.AttributeReflector;
import org.moxie.ant.MxTest;
import org.moxie.maxml.MaxmlMap;

/**
 * Utility class for Cobertura code-coverage.
 */
public class Cobertura {

	public static void instrument(MxTest mxtest) {
		FileSet fileSet = new FileSet();
		fileSet.setProject(mxtest.getProject());
		fileSet.setDir(mxtest.getClassesDir());
		fileSet.createInclude().setName("**/*.class");
		
		InstrumentTask task = new InstrumentTask();
		task.setTaskName("instr");
		task.setProject(mxtest.getProject());
		task.init();

		task.addFileset(fileSet);
		task.setDataFile(mxtest.getCoberturaData().getAbsolutePath());
		task.setToDir(mxtest.getInstrumentedBuild());
		// TODO: cobertura is dead and does not support modern Java
//		task.execute();
	}
	
	public static void report(MxTest mxtest) {
		CoberturaReportTask task = new CoberturaReportTask();
		task.setTaskName("report");
		task.setProject(mxtest.getProject());
		task.init();
		
		task.setDataFile(mxtest.getCoberturaData().getAbsolutePath());
		task.setDestDir(mxtest.getCoverageReports());
		
		MaxmlMap attributes = mxtest.getBuild().getConfig().getTaskAttributes("cobertura");
		if (attributes != null) {
			AttributeReflector.setAttributes(mxtest.getProject(), task, attributes);
		}

		Path path = new Path(mxtest.getProject());
		path.setRefid(new Reference(mxtest.getProject(), Key.compileSourcePath.referenceId()));
		task.addPath(path);
		// TODO: cobertura is dead and does not support modern Java
//		task.execute();
	}
}
