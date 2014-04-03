/**
 * Axelor Business Solutions
 *
 * Copyright (C) 2012-2014 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.axelor.text;

import groovy.text.GStringTemplateEngine;
import groovy.text.TemplateEngine;
import groovy.xml.XmlUtil;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.axelor.common.ClassUtils;
import com.axelor.common.StringUtils;
import com.axelor.db.Model;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.meta.db.MetaSelectItem;
import com.axelor.script.ScriptBindings;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;

/**
 * The implementation of {@link Templates} for groovy string template support.
 * 
 */
public class GroovyTemplates implements Templates {

	private static final TemplateEngine engine = new GStringTemplateEngine();

	class GroovyTemplate implements Template {

		private String text;

		public GroovyTemplate(String text) {
			this.text = text;
		}

		private String process(String text) {
			if (StringUtils.isBlank(text)) {
				return "";
			}
			text = text.replaceAll("\\$\\{\\s*(\\w+)(\\?)?\\.([^}]*?)\\s*\\|\\s*text\\s*\\}", "\\${__fmt__.text($1, '$3')}");
			text = text.replaceAll("\\$\\{\\s*([^}]*?)\\s*\\|\\s*text\\s*\\}", "\\${__fmt__.text('$1')}");
			text = text.replaceAll("\\$\\{\\s*([^}]*?)\\s*\\|\\s*e\\s*\\}", "\\${($1) ?: ''}");
			if (text.trim().startsWith("<?xml ")) {
				text = text.replaceAll("\\$\\{(.*?)\\}", "\\${__fmt__.escape($1)}");
			}
			return text;
		}
		
		@Override
		public Renderer make(final Map<String, Object> context) {
			final ScriptBindings bindings = new ScriptBindings(context);
			String text = this.text;
			try {
				Class<?> klass = ClassUtils.findClass((String) bindings.get("_model"));
				bindings.put("__fmt__", new FormatHelper(klass, bindings));
				text = process(text);
			} catch (Exception e) {
			}

			
			try {
				final groovy.text.Template template = engine.createTemplate(text);
				return new Renderer() {
					
					@Override
					public void render(Writer out) throws IOException {
						template.make(bindings).writeTo(out);
					}
				};
			} catch (Exception e) {
				throw Throwables.propagate(e);
			}
		}

		@Override
		@SuppressWarnings("serial")
		public <T extends Model> Renderer make(final T context) {
			final Mapper mapper = context == null ? null : Mapper.of(context.getClass());
			final Map<String, Object> ctx = new HashMap<String, Object>() {
				
				@Override
				public boolean containsKey(Object key) {
					return mapper != null && mapper.getProperty((String) key) != null;
				}
				
				@Override
				public Object get(Object key) {
					return mapper == null ? null : mapper.get(context, (String) key);
				}
			};
			return make(ctx);
		}
	}
	
	class FormatHelper {

		private final Logger log = LoggerFactory.getLogger(FormatHelper.class);
		
		private Class<?> contextClass;
		private Map<String, Object> context;

		public FormatHelper(Class<?> entityClass, Map<String, Object> bindings) {
			this.contextClass = entityClass;
			this.context = bindings;
		}

		public Object escape(Object value) {
			if (value == null) {
				return "";
			}
			return XmlUtil.escapeXml(value.toString());
		}

		public String text(String expr) {
			return getSelectTitle(contextClass, expr, context.get(expr));
		}

		public String text(Object bean, String expr) {
			if (bean == null) {
				return "";
			}
			expr = expr.replaceAll("\\?", "");
			return getSelectTitle(bean.getClass(), expr, getValue(bean, expr));
		}

		private String getSelectTitle(Class<?> klass, String expr, Object value) {
			if (value == null) {
				return "";
			}
			Property property = this.getProperty(klass, expr);
			if (property == null || property.getSelection() == null) {
				return value == null ? "" : value.toString();
			}
			MetaSelectItem item = MetaSelectItem
					.all()
					.filter("self.select.name = ?1 AND self.value = ?2",
							property.getSelection(), value).fetchOne();
			if (item != null) {
				return item.getTitle();
			}
			return value == null ? "" : value.toString();
		}

		private Property getProperty(Class<?> beanClass, String name) {
			Iterator<String> iter = Splitter.on(".").split(name).iterator();
			Property p = Mapper.of(beanClass).getProperty(iter.next());
			while(iter.hasNext() && p != null) {
				p = Mapper.of(p.getTarget()).getProperty(iter.next());
			}
			return p;
		}

		@SuppressWarnings("all")
		private Object getValue(Object bean, String expr) {
			if (bean == null) return null;
			Iterator<String> iter = Splitter.on(".").split(expr).iterator();
			Object obj = null;
			if (bean instanceof Map) {
				obj = ((Map) bean).get(iter.next());
			} else {
				obj = Mapper.of(bean.getClass()).get(bean, iter.next());
			}
			if(iter.hasNext() && obj != null) {
				return getValue(obj, Joiner.on(".").join(iter));
			}
			return obj;
		}

		public void info(String text,  Object... params) {
			log.info(text, params);
		}

		public void debug(String text,  Object... params) {
			log.debug(text, params);
		}

		public void error(String text,  Object... params) {
			log.error(text, params);
		}

		public void trace(String text,  Object... params) {
			log.trace(text, params);
		}
	}
	
	@Override
	public Template fromText(String text) {
		return new GroovyTemplate(text);
	}
}
