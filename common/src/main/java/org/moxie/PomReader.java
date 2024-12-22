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
package org.moxie;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.moxie.Constants.Key;
import org.moxie.MoxieException.MissingParentPomException;
import org.moxie.utils.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;


public class PomReader {

	/**
	 * Enum to control how strictly the PomReader enforces missing or incomplete
	 * data.
	 */
	public static enum Requirements {
		STRICT(true, true), LOOSE(false, false);
		
		final boolean requireParent;
		final boolean resolveProperties;
		
		Requirements(boolean requireParent, boolean resolveProperties) {
			this.requireParent = requireParent;
			this.resolveProperties = resolveProperties;
		}
	}
	
	/**
	 * Reads a POM file from an artifact cache.  Parent POMs will be read and
	 * applied automatically, if they exist in the cache.
	 * 
	 * @param cache
	 * @param dependency
	 * @return
	 * @throws Exception
	 */
	public static Pom readPom(IMavenCache cache, Dependency dependency) {
		File pomFile = cache.getArtifact(dependency, Constants.POM);
		if (!pomFile.exists()) {
			return null;
		}
		return readPom(cache, pomFile, Requirements.STRICT);
	}
	
	/**
	 * Reads a POM file from an artifact cache.  Parent POMs will be read and
	 * applied automatically, if they exist in the cache.
	 * 
	 * @param cache
	 * @param pomFile
	 * @return
	 * @throws Exception
	 */
	public static Pom readPom(IMavenCache cache, File pomFile) {	
		return readPom(cache, pomFile, Requirements.STRICT);
	}
	
	/**
	 * Reads a POM file from an artifact cache.  Parent POMs will be read and
	 * applied automatically, if they exist in the cache.
	 * 
	 * @param cache
	 * @param pomFile
	 * @param requirements
	 * @return
	 * @throws Exception
	 */
	public static Pom readPom(IMavenCache cache, File pomFile, Requirements requirements) {
		Set<Dependency> importBOMs = new LinkedHashSet<>();
		return readPom(cache, pomFile, requirements, importBOMs);
	}

	/**
	 * Reads and parses a Maven Project Object Model (POM) file, extracting metadata and resolving
	 * dependencies, properties, and inheritance based on the provided requirements and context.
	 *
	 * @param cache          The {@code IMavenCache} instance used to cache and retrieve Maven metadata.
	 * @param pomFile        The {@code File} representation of the POM file to be read.
	 * @param requirements   The {@code Requirements} object dictating property resolution, parent POM requirements,
	 *                       and other parsing constraints.
	 * @param importBOMs     A {@code Set} of {@code Dependency} objects representing imported BOMs when applicable.
	 * @return A {@code Pom} object containing the structured metadata, dependencies, and properties of the POM.
	 *         The returned object incorporates both the properties defined in the POM and those inherited or resolved.
	 * @throws RuntimeException If there is an error while reading or parsing the POM file.
	 * @throws MissingParentPomException If the parent POM is required but cannot be resolved or located.
	 */
	public static Pom readPom(IMavenCache cache, File pomFile, Requirements requirements, Set<Dependency> importBOMs) {
		Document doc = null;
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			DocumentBuilder builder = factory.newDocumentBuilder();
			doc = builder.parse(pomFile);
			doc.getDocumentElement().normalize();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
				
		Element docElement = doc.getDocumentElement();
		
		Pom pom = new Pom();
		List<Dependency> managedList = new ArrayList<Dependency>();
		List<Dependency> dependencyList = new ArrayList<Dependency>();
		
		NodeList projectNodes = docElement.getChildNodes();
		for (int i = 0; i < projectNodes.getLength(); i++) {
			Node pNode = projectNodes.item(i);
			if (pNode.getNodeType() == Node.ELEMENT_NODE) {
				Element element = (Element) pNode;				
				if ("parent".equalsIgnoreCase(element.getTagName())) {
					// parent properties	
					pom.parentGroupId = readStringTag(pNode, Key.groupId);
					pom.parentArtifactId = readStringTag(pNode, Key.artifactId);
					pom.parentVersion = readStringTag(pNode, Key.version);
										
					// read parent pom
					Dependency parent = pom.getParentDependency();
					Pom parentPom = readPom(cache, parent);
					
					if (parentPom == null) {
						// we do not have the parent POM in the cache
						if (requirements.requireParent) {
							// notify the caller of the missing POM
							throw new MissingParentPomException(parent);
						}
						// loose parsing option:
						// we do not have the parent pom yet likely because we
						// are in the middle of downloading so make a fake one
						// to satisfy ${parent.} property inheritance
						parentPom = new Pom();
						parentPom.groupId = pom.parentGroupId;
						parentPom.artifactId = pom.parentArtifactId;
						parentPom.version = pom.parentVersion;
					}
					pom.inherit(parentPom);
				} else if ("properties".equalsIgnoreCase(element.getTagName())) {
					// pom properties
					NodeList properties = (NodeList) element;
					for (int j = 0; j < properties.getLength(); j++) {
						Node node = properties.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							String property = node.getNodeName();							
							if (node.getFirstChild() != null) {							
								pom.setProperty(property, node.getFirstChild().getNodeValue());
							}
						}						
					}
				} else if ("dependencyManagement".equalsIgnoreCase(element.getTagName())) {
					// dependencyManagement definitions
					NodeList dependencies = element.getElementsByTagName("dependency");
					for (int j = 0, jlen = dependencies.getLength(); j < jlen; j++) {
						Node node = dependencies.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							// dependencyManagement.dependency
							Dependency dep = readDependency(node, pom);
							Scope scope = Scope.fromString(readStringTag(node, Key.scope));
							dep.definedScope = scope;

							managedList.add(dep);
						}
					}
				} else if ("dependencies".equalsIgnoreCase(element.getTagName())) {
					// read dependencies
					NodeList dependencies = (NodeList) element;
					for (int j = 0; j < dependencies.getLength(); j++) {
						Node node = dependencies.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							// dependencies.dependency
							Dependency dep = readDependency(node, pom);
							Scope scope = Scope.fromString(readStringTag(node, Key.scope));
							if (scope == null) {
								scope = Scope.compile;
							}
                            dep.definedScope = scope;
                           	dependencyList.add(dep);
						}
					}
				} else if ("licenses".equalsIgnoreCase(element.getTagName())) {
					// read licenses
					// do not inherit licenses as this pom defines them
					pom.clearLicenses();
					NodeList licenses = (NodeList) element;
					for (int j = 0; j < licenses.getLength(); j++) {
						Node node = licenses.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							// licenses.license
							String name = readStringTag(node, Key.name);
							String url = readStringTag(node, Key.url);
							License license = new License(name, url);
							license.distribution = readStringTag(node, Key.distribution);
							license.comments = readStringTag(node, Key.comments);
							pom.addLicense(license);
						}
					}
				} else if ("developers".equalsIgnoreCase(element.getTagName())) {
					// read developers
					NodeList developers = (NodeList) element;
					for (int j = 0; j < developers.getLength(); j++) {
						Node node = developers.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							// developers.developer
							Person person = readPerson(node);							
							pom.addDeveloper(person);
						}
					}
				} else if ("contributors".equalsIgnoreCase(element.getTagName())) {
					// read contributors
					NodeList contributors = (NodeList) element;
					for (int j = 0; j < contributors.getLength(); j++) {
						Node node = contributors.item(j);
						if (node.getNodeType() == Node.ELEMENT_NODE) {
							// contributors.contributor
							Person person = readPerson(node);							
							pom.addContributor(person);
						}
					}
				} else if ("scm".equalsIgnoreCase(element.getTagName())) {
					// scm properties	
					pom.scm.connection = readStringTag(pNode, Key.connection);
					pom.scm.developerConnection = readStringTag(pNode, Key.developerConnection);
					pom.scm.url = readStringTag(pNode, Key.url);
					pom.scm.tag = readStringTag(pNode, Key.tag);					
				} else if ("issueManagement".equalsIgnoreCase(element.getTagName())) {
					// extract the issue tracker url
					pom.issuesUrl = readStringTag(element, Key.url);
				} else if ("groupId".equalsIgnoreCase(element.getTagName())) {
					// extract the groupId
					pom.groupId = readStringTag(element);
				} else if ("artifactId".equalsIgnoreCase(element.getTagName())) {
					// extract the artifactId
					pom.artifactId = readStringTag(element);
				} else if ("version".equalsIgnoreCase(element.getTagName())) {
					// extract the version
					pom.version = readStringTag(element);
				} else if ("packaging".equalsIgnoreCase(element.getTagName())) {
					// extract the packaging
					pom.packaging = readStringTag(element);
				} else if ("name".equalsIgnoreCase(element.getTagName())) {
					// extract the name
					pom.name = readStringTag(element);
				} else if ("description".equalsIgnoreCase(element.getTagName())) {
					// extract the description
					pom.description = readStringTag(element);
				} else if ("url".equalsIgnoreCase(element.getTagName())) {
					// extract the url
					pom.url = readStringTag(element);
				} else if ("organization".equalsIgnoreCase(element.getTagName())) {
					// extract the organization data
					pom.organization = readStringTag(element, Key.name);
					pom.organizationUrl = readStringTag(element, Key.url);
				} else if ("inceptionYear".equalsIgnoreCase(element.getTagName())) {
					// extract the inception year
					pom.inceptionYear = readStringTag(element);
				}
			}
		}
		
		if (requirements.resolveProperties) {
			pom.resolveProperties();
		}
		
		// Add managed dependencies after resolving all properties
		for (Dependency dep : managedList) {
			if (Scope.imprt.equals(dep.definedScope)) {
				// dependencyManagement import 
				Pom importPom = readPom(cache, dep);
				if (importPom != null) {
					pom.importManagedDependencies(importPom);
				} else {
					importBOMs.add(dep);
				}
			} else {
				// add dependency management definition
				pom.addManagedDependency(dep, dep.definedScope,
						requirements.resolveProperties);
			}
		}
		
		// Add dependencies after adding all managed dependencies
		for (Dependency dep : dependencyList) {
			 Scope addedScope = pom.addDependency(dep, dep.definedScope,
					 requirements.resolveProperties);
			 dep.definedScope = addedScope;
		}
		return pom;
	}
	
	private static Dependency readDependency(Node node, Pom pom) {
		Dependency dep = new Dependency();
		dep.groupId = pom.resolveProperties(readStringTag(node, Key.groupId));
		dep.artifactId = pom.resolveProperties(readStringTag(node, Key.artifactId));
		dep.version = pom.resolveProperties(readStringTag(node, Key.version));
		if (dep.version == null && "org.ow2.asm".equals(dep.groupId)) {
			dep.version = pom.resolveProperties("${asm.version}");
			if (dep.version == null) {
				dep.version = "9.7";
			}
		}
		dep.classifier = readStringTag(node, Key.classifier);
		dep.type = readStringTag(node, Key.type);
		dep.extension = Constants.getExtension(dep.type);
		dep.optional = readBooleanTag(node, Key.optional);
		dep.exclusions.addAll(readExclusions(node));
		return dep;
	}

	private static Person readPerson(Node node) {
		Person person = new Person();
		person.id = readStringTag(node, Key.id);
		person.name = readStringTag(node, Key.name);
		person.email = readStringTag(node, Key.email);
		person.url = readStringTag(node, Key.url);
		person.organization = readStringTag(node, Key.organization);
		person.organizationUrl = readStringTag(node, Key.organizationUrl);
		
		person.roles = new ArrayList<String>();
		NodeList roles = ((Element) node).getElementsByTagName("role");
		for (int i = 0; i < roles.getLength(); i++) {
			person.roles.add(readStringTag(roles.item(i)));
		}
		return person;
	}

	private static String readStringTag(Node node, Key tag) {
		Element element = (Element) node;
		NodeList tagList = element.getElementsByTagName(tag.name());
		if (tagList == null || tagList.getLength() == 0) {
			return null;
		}
		Element tagElement = (Element) tagList.item(0);
		NodeList textList = tagElement.getChildNodes();
		Node itemNode = textList.item(0);
		if (itemNode == null) {
			return null;
		}
		String content = itemNode.getNodeValue().trim();
		return content;
	}
	
	private static String readStringTag(Node node) {
		if (node == null) {
			return null;
		}
		Node tagElement = node.getFirstChild();
		if (tagElement == null) {
			return null;
		}
		String content = tagElement.getTextContent();
		return content;		
	}


	private static boolean readBooleanTag(Node node, Key tag) {
		String content = readStringTag(node, tag);
		if (StringUtils.isEmpty(content)) {
			return false;
		}
		return Boolean.parseBoolean(content);
	}
	
	private static Collection<String> readExclusions(Node node) {
		Set<String> exclusions = new LinkedHashSet<String>();
		Element element = (Element) node;
		NodeList exclusionList = element.getElementsByTagName("exclusion");
		if (exclusionList == null || exclusionList.getLength() == 0) {
			return exclusions;
		}
		
		for (int i = 0; i < exclusionList.getLength(); i++) {
			Node exclusionNode = exclusionList.item(i);
			String groupId = readStringTag(exclusionNode, Key.groupId);
			String artifactId = readStringTag(exclusionNode, Key.artifactId);
			if (StringUtils.isEmpty(artifactId)) {
				// group exclusion
				exclusions.add(groupId);
			} else {
				// artifact exclusion
				exclusions.add(groupId + ":" + artifactId);
			}
		}
		return exclusions;
	}
	
}
