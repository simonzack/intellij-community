/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.openapi.keymap.impl;

import com.intellij.ide.WelcomeWizardUtil;
import com.intellij.openapi.actionSystem.IdeActions;
import com.intellij.openapi.components.*;
import com.intellij.openapi.keymap.Keymap;
import com.intellij.openapi.keymap.KeymapManagerListener;
import com.intellij.openapi.keymap.ex.KeymapManagerEx;
import com.intellij.openapi.options.BaseSchemeProcessor;
import com.intellij.openapi.options.SchemesManager;
import com.intellij.openapi.options.SchemesManagerFactory;
import com.intellij.openapi.util.InvalidDataException;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.util.containers.ContainerUtil;
import org.jdom.Document;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.event.KeyEvent;
import java.util.*;

@State(
  name = "KeymapManager",
  storages = @Storage(file = StoragePathMacros.APP_CONFIG + "/keymap.xml", roamingType = RoamingType.PER_PLATFORM),
  additionalExportFile = KeymapManagerImpl.KEYMAPS_DIR_PATH
)
public class KeymapManagerImpl extends KeymapManagerEx implements PersistentStateComponent<Element>, ApplicationComponent {
  static final String KEYMAPS_DIR_PATH = StoragePathMacros.ROOT_CONFIG + "/keymaps";

  private final List<KeymapManagerListener> myListeners = ContainerUtil.createLockFreeCopyOnWriteList();
  private String myActiveKeymapName;
  private final Map<String, String> myBoundShortcuts = new HashMap<String, String>();

  @NonNls private static final String KEYMAP = "keymap";
  @NonNls private static final String ACTIVE_KEYMAP = "active_keymap";
  @NonNls private static final String NAME_ATTRIBUTE = "name";
  private final SchemesManager<Keymap, KeymapImpl> mySchemesManager;

  public static boolean ourKeymapManagerInitialized = false;

  KeymapManagerImpl(DefaultKeymap defaultKeymap, SchemesManagerFactory factory) {
    mySchemesManager = factory.createSchemesManager(KEYMAPS_DIR_PATH,
                                                    new BaseSchemeProcessor<KeymapImpl>() {
                                                      @Override
                                                      public KeymapImpl readScheme(@NotNull final Document schemeContent) throws InvalidDataException {
                                                        return readKeymap(schemeContent);
                                                      }

                                                      @Override
                                                      public Element writeScheme(@NotNull final KeymapImpl scheme) {
                                                        return scheme.writeExternal();
                                                      }

                                                      @Override
                                                      public boolean shouldBeSaved(@NotNull final KeymapImpl scheme) {
                                                        return scheme.canModify();
                                                      }
                                                    },
                                                    RoamingType.PER_USER);

    Keymap[] keymaps = defaultKeymap.getKeymaps();
    String systemDefaultKeymap = WelcomeWizardUtil.getWizardMacKeymap() != null
                                 ? WelcomeWizardUtil.getWizardMacKeymap()
                                 : defaultKeymap.getDefaultKeymapName();
    for (Keymap keymap : keymaps) {
      addKeymap(keymap);
      if (systemDefaultKeymap.equals(keymap.getName())) {
        setActiveKeymap(keymap);
      }
    }
    mySchemesManager.loadSchemes();

    if (Registry.is("editor.add.carets.on.double.control.arrows")) {
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_CLONE_CARET_ABOVE, KeyEvent.VK_CONTROL, KeyEvent.VK_UP);
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_CLONE_CARET_BELOW, KeyEvent.VK_CONTROL, KeyEvent.VK_DOWN);
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_MOVE_CARET_LEFT_WITH_SELECTION, KeyEvent.VK_CONTROL, KeyEvent.VK_LEFT);
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_MOVE_CARET_RIGHT_WITH_SELECTION, KeyEvent.VK_CONTROL, KeyEvent.VK_RIGHT);
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_MOVE_LINE_START_WITH_SELECTION, KeyEvent.VK_CONTROL, KeyEvent.VK_HOME);
      ModifierKeyDoubleClickHandler.getInstance().registerAction(IdeActions.ACTION_EDITOR_MOVE_LINE_END_WITH_SELECTION, KeyEvent.VK_CONTROL, KeyEvent.VK_END);
    }

    //noinspection AssignmentToStaticFieldFromInstanceMethod
    ourKeymapManagerInitialized = true;
  }

  @Override
  public Keymap[] getAllKeymaps() {
    List<Keymap> answer = new ArrayList<Keymap>();
    for (Keymap keymap : mySchemesManager.getAllSchemes()) {
      if (!keymap.getPresentableName().startsWith("$")) {
        answer.add(keymap);
      }
    }
    return answer.toArray(new Keymap[answer.size()]);
  }

  public Keymap[] getAllIncludingDefaultsKeymaps() {
    Collection<Keymap> keymaps = mySchemesManager.getAllSchemes();
    return keymaps.toArray(new Keymap[keymaps.size()]);
  }

  @Override
  @Nullable
  public Keymap getKeymap(String name) {
    return mySchemesManager.findSchemeByName(name);
  }

  @Override
  public Keymap getActiveKeymap() {
    return mySchemesManager.getCurrentScheme();
  }

  @Override
  public void setActiveKeymap(Keymap activeKeymap) {
    mySchemesManager.setCurrentSchemeName(activeKeymap == null ? null : activeKeymap.getName());
    fireActiveKeymapChanged();
  }

  @Override
  public void bindShortcuts(String sourceActionId, String targetActionId) {
    myBoundShortcuts.put(targetActionId, sourceActionId);
  }

  @Override
  public void unbindShortcuts(String targetActionId) {
    myBoundShortcuts.remove(targetActionId);
  }

  @Override
  public Set<String> getBoundActions() {
    return myBoundShortcuts.keySet();
  }

  @Override
  public String getActionBinding(String actionId) {
    return myBoundShortcuts.get(actionId);
  }

  @Override
  public SchemesManager<Keymap, KeymapImpl> getSchemesManager() {
    return mySchemesManager;
  }

  public void addKeymap(Keymap keymap) {
    mySchemesManager.addNewScheme(keymap, true);
  }

  public void removeAllKeymapsExceptUnmodifiable() {
    for (Keymap keymap : mySchemesManager.getAllSchemes()) {
      if (keymap.canModify()) {
        mySchemesManager.removeScheme(keymap);
      }
    }
    mySchemesManager.setCurrentSchemeName(null);

    Collection<Keymap> keymaps = mySchemesManager.getAllSchemes();
    if (keymaps.size() > 0) {
      mySchemesManager.setCurrentSchemeName(keymaps.iterator().next().getName());
    }
  }

  @Override
  public Element getState() {
    Element result = new Element("component");
    if (mySchemesManager.getCurrentScheme() != null) {
      Element e = new Element(ACTIVE_KEYMAP);
      Keymap currentScheme = mySchemesManager.getCurrentScheme();
      if (currentScheme != null) {
        e.setAttribute(NAME_ATTRIBUTE, currentScheme.getName());
      }
      result.addContent(e);
    }
    return result;
  }

  @Override
  public void loadState(final Element state) {
    Element child = state.getChild(ACTIVE_KEYMAP);
    if (child != null) {
      myActiveKeymapName = child.getAttributeValue(NAME_ATTRIBUTE);
    }

    if (myActiveKeymapName != null) {
      Keymap keymap = getKeymap(myActiveKeymapName);
      if (keymap != null) {
        setActiveKeymap(keymap);
      }
    }
  }

  @NotNull
  private KeymapImpl readKeymap(Document document) throws InvalidDataException {
    if (document == null) throw new InvalidDataException();
    Element root = document.getRootElement();
    if (!KEYMAP.equals(root.getName())) {
      throw new InvalidDataException();
    }
    KeymapImpl keymap = new KeymapImpl();
    keymap.readExternal(root, getAllIncludingDefaultsKeymaps());
    return keymap;
  }

  private void fireActiveKeymapChanged() {
    for (KeymapManagerListener listener : myListeners) {
      listener.activeKeymapChanged(mySchemesManager.getCurrentScheme());
    }
  }

  @Override
  public void addKeymapManagerListener(@NotNull KeymapManagerListener listener) {
    pollQueue();
    myListeners.add(listener);
  }

  private void pollQueue() {
    // assume it is safe to remove elements during iteration, as is the case with the COWAL
    for (KeymapManagerListener listener : myListeners) {
      if (listener instanceof WeakKeymapManagerListener && ((WeakKeymapManagerListener)listener).isDead()) {
        myListeners.remove(listener);
      }
    }
  }

  @Override
  public void removeKeymapManagerListener(@NotNull KeymapManagerListener listener) {
    pollQueue();
    myListeners.remove(listener);
  }

  @Override
  public void addWeakListener(@NotNull KeymapManagerListener listener) {
    addKeymapManagerListener(new WeakKeymapManagerListener(this, listener));
  }

  @Override
  public void removeWeakListener(@NotNull KeymapManagerListener listenerToRemove) {
    // assume it is safe to remove elements during iteration, as is the case with the COWAL
    for (KeymapManagerListener listener : myListeners) {
      if (listener instanceof WeakKeymapManagerListener && ((WeakKeymapManagerListener)listener).isWrapped(listenerToRemove)) {
        myListeners.remove(listener);
      }
    }
  }

  @Override
  @NotNull
  public String getComponentName() {
    return "KeymapManager";
  }

  @Override
  public void initComponent() {
  }

  @Override
  public void disposeComponent() {
  }
}
