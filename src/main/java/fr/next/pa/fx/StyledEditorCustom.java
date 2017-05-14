package fr.next.pa.fx;

import com.sun.webkit.graphics.WCPoint;

import fr.next.pa.api.ActionInputCallBack;
import fr.next.pa.api.DisplayOnAction;
import javafx.print.PrinterJob;
import javafx.scene.control.Control;
import javafx.scene.control.Skin;

/**
 * Redirect actions to StyledEditorSkinCustom.
 */
public class StyledEditorCustom extends Control {

	private DisplayOnAction displayOnAction;

	public StyledEditorCustom(DisplayOnAction displayOnAction) {
		this.displayOnAction = displayOnAction;
		getStyleClass().add(Style.CODEAREA);
	}

	@Override
	protected Skin<?> createDefaultSkin() {
		return new StyledEditorSkinCustom(this, displayOnAction);
	}

	private StyledEditorSkinCustom skin() {
		return (StyledEditorSkinCustom) getSkin();
	}

	public void print(PrinterJob job) {
		skin().print(job);
	}

	public String getSelectedText() {
		return skin().getSelectedText();
	}

	public int getCaretPosition() {
		return skin().getCaretPosition();
	}

	public WCPoint getCaretBounds() {
		return skin().getCaretBounds();
	}

	public WCPoint getMouseBounds() {
		return skin().getMouseBounds();
	}

	public void println(String msg) {
		skin().println(msg);
	}

	public void insertText(int offsets, String msg) {
		skin().insertText(offsets, msg);
	}

	public void replaceText(int start, int end, String msg) {
		skin().replaceText(start, end, msg);
	}

	public String getText() {
		return skin().getText();
	}

	public void removeHighlights() {
		skin().removeHighlights();
	}

	public void highlight(int offset, int length, String type) {
		skin().highlight(offset, length, type);
	}

	public void reInitTemplates() {
		skin().reInitTemplates();
	}

	public void actionPopup(ActionInputCallBack actionInputCallBack, String msg) {
		skin().actionPopup(actionInputCallBack, msg);
	}
}