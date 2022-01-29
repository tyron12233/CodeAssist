## Introduction

Developers can easily add functionalities to existing IDE menus and toolbars or add new menus 
altogether using the Actions API.

## Creating a Custom Action

Custom Actions in CodeAssist are implemented by extending the abstract class [AnAction](https://github.com/tyron12233/CodeAssist/blob/91f91294c07570fb888c24e592c5b1f847cecc74/actions-api/src/main/java/com/tyron/actions/AnAction.java)
Classes that extend it should override `AnAction.update()` and `AnAction.actionPerformed()`.

- The `update` method implements the code that updates the appearance of the action
- The `actionPerformed` method implements the code that will be run when the user has selected
the action.
  
## Action Places

There are different places on which a custom action can be shown. See [ActionPlaces](https://github.com/tyron12233/CodeAssist/blob/91f91294c07570fb888c24e592c5b1f847cecc74/actions-api/src/main/java/com/tyron/actions/ActionPlaces.java)
for a list of places CodeAssist currently supports. 
  
## Example

This example shows how to create an Action to save the current file in the IDE.

### Subclassing AnAction

```java
import androidx.annotation.NonNull;

import com.tyron.actions.AnAction;
import com.tyron.actions.AnActionEvent;

public class SaveAction extends AnAction {
    @Override
    public void update(@NonNull AnActionEvent event) {
        
    }

    @Override
    public void actionPerformed(@NonNull AnActionEvent e) {
        
    }
}
```

Currently this action does not do anything useful and does not have a text that would represent it.

### Extending the update() method

T he `AnActionEvent` object which is passed in the method, contains context information about
where the current event has taken place. See [AnActionEvent.getData()](https://github.com/tyron12233/CodeAssist/blob/91f91294c07570fb888c24e592c5b1f847cecc74/actions-api/src/main/java/com/tyron/actions/AnActionEvent.java#L57) 
and [CommonDataKeys](https://github.com/tyron12233/CodeAssist/blob/91f91294c07570fb888c24e592c5b1f847cecc74/actions-api/src/main/java/com/tyron/actions/CommonDataKeys.java)
for a list of objects that can be retrieved.

Determine which objects do you need in the update method, that way it will be guaranteed to be 
not null when the user has clicked on the action.
```java
@Override
public void update(@NonNull AnActionEvent event) {
    Presentation presentation = event.getPresentation();
    // set the presentation to be not visible by default, that way methods can just
    // return right away if it has been determined that its not the right place for this action.
    presentation.setVisible(false);
    
    // this ensures that the action is only visible on the Main Toolbar.
    if (!ActionPlaces.MAIN_TOOLBAR.equals(event.getPlace())) {
        return;
    }
    
    // the fragment data key returns the current focused fragment when this action was invoked.
    Fragment fragment = event.getData(CommonDataKeys.FRAGMENT);
    if (fragment == null) {
        return;
    }
    
    // if the code has reached here, it has been determined that this action should be visible
    presentation.setVisible(true);
    presentation.setText("Save");
}
```
**The update method is called frequently on the UI thread. This method needs to execute very quickly and 
no real work should be performed in this method. For example working with the file system such as reading
and writing to files are considered invalid and will block the UI thread.**

## Extending the actionPerformed() method

CodeAssist has a [Savable](https://github.com/tyron12233/CodeAssist/blob/main/app/src/main/java/com/tyron/code/ui/editor/Savable.java) interface
which its editors implement to indicate that its content can be saved. This action leverages that feature.

```java
@Override 
public void actionPerformed(@NonNull AnActionEvent e) {
    Fragment fragment = e.getRequiredData(CommonDataKeys.FRAGMENT);
    if (fragment instanceof Savable) {
        ((Savable) fragment).save();
    }
}
```

### Registering the action

Currently, actions need to be registered dynamically through the [ActionManager](https://github.com/tyron12233/CodeAssist/blob/main/app/src/main/java/com/tyron/code/ui/editor/Savable.java)
Actions need to have a unique String identifier that the Actions API will use to identify it from others.
```java
ActionManager.getInstance().registerAction(UNIQUE_ID, new SaveAction());
```
