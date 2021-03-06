package fr.next.pa.fx;

import java.awt.AWTException;
import java.awt.Robot;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import com.sun.javafx.application.PlatformImpl;
import com.sun.javafx.scene.control.skin.BehaviorSkinBase;
import com.sun.javafx.scene.control.skin.FXVK;
import com.sun.javafx.webkit.Accessor;
import com.sun.webkit.WebPage;
import com.sun.webkit.event.WCKeyEvent;
import com.sun.webkit.graphics.WCPoint;

import fr.next.pa.api.ActionInputCallBack;
import fr.next.pa.api.DisplayOnAction;
import javafx.animation.PauseTransition;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.print.PrinterJob;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import javafx.scene.input.KeyCode;
import javafx.event.Event;
import java.util.concurrent.TimeUnit;

/**
 * Skin of styled editor. Based on HTML tags. Update the style on action.
 */
public class StyledEditorSkinCustom extends BehaviorSkinBase<StyledEditorCustom, StyledEditorBehaviorCustom> {

	/** System and of line **/
	private static final String EOL = System.lineSeparator();

	/** HighLight style **/
	private HighLight highLight = new HighLight();

	/** grid pane of styled editor **/
	private GridPane gridPane;

	/** web view **/
	private WebView webView;

	/** web page **/
	private WebPage webPage;

	/** current HTML text **/
	private String cachedHTMLText = "<html><head><link rel=\"stylesheet\" href=\""
			+ getClass().getResource("/" + Style.CSSFILE) + "\"></head><body class=\"" + Style.CODEAREA + " "
			+ Style.DEFAULT + "\" contenteditable=\"true\"></body></html>";

	/** autocompletion message **/
	private String autoCompletionMessage;

	/** remove component on demand **/
	private ListChangeListener<Node> itemsListener = c -> {
		while (c.next()) {
			if (c.getRemovedSize() > 0) {
				for (Node n : c.getList()) {
					if (n instanceof WebView) {
						webPage.dispose();
					}
				}
			}
		}
	};

	/** caret position line offset **/
	private int caretLinePos;

	/** caret position column effset **/
	private int caretColPos;

	/** caret bounds **/
	private WCPoint caretBounds;

	/** horizontal scroll position **/
	private int scrollHPosition;

	/** vertical scroll position **/
	private int scrollVPosition;

	/** update the style of the current text **/
	private PauseTransition pauseUpdateStyle;

	/** mouse position scene X **/
	private double mouseScreenX;

	/** mouse position scene Y **/
	private double mouseScreenY;

	/** focus **/
	private static PseudoClass CONTAINS_FOCUS_PSEUDOCLASS_STATE = PseudoClass.getPseudoClass("contains-focus");

	/** monitor to avoid concurrent modification on highLights **/
	private Object monitorHighLights = new Object();

	/** previous caret position **/
	private int[] previousCaretPosition;

	/** bounds in scene **/
	private Bounds boundsInScene;

	public StyledEditorSkinCustom(StyledEditorCustom htmlEditor, DisplayOnAction displayOnAction) {
		super(htmlEditor, new StyledEditorBehaviorCustom(htmlEditor, displayOnAction));

		getChildren().clear();

		gridPane = new GridPane();
		gridPane.getStyleClass().add("grid");
		getChildren().addAll(gridPane);

		webView = new WebView();
		webView.setContextMenuEnabled(false);
		gridPane.add(webView, 0, 2);
		ColumnConstraints column = new ColumnConstraints();
		column.setHgrow(Priority.ALWAYS);
		gridPane.getColumnConstraints().add(column);

		webPage = Accessor.getPageFor(webView.getEngine());

		updateCaretAndScrollPosition();

		PauseTransition pause = new PauseTransition(Duration.seconds(2));
		webView.addEventHandler(MouseEvent.ANY, e -> Platform.runLater(() -> {
			updateCaretAndScrollPosition();
			pause.setOnFinished(event -> ((StyledEditorBehaviorCustom) getBehavior())
					.showToolTip(displayOnAction.tooltip(getCaretPosition())));
			pause.playFromStart();
			((StyledEditorBehaviorCustom) getBehavior()).hideToolTip();

		}));

		webView.addEventHandler(KeyEvent.ANY, e -> Platform.runLater(() -> {
			updateCaretAndScrollPosition();
			pause.playFromStart();
			((StyledEditorBehaviorCustom) getBehavior()).hideToolTip();
		}));

		pauseUpdateStyle = new PauseTransition(Duration.seconds(1));
		pauseUpdateStyle.setOnFinished(event -> updateStyle());

		getSkinnable().focusedProperty().addListener((observable, oldValue, newValue) -> {
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					if (newValue) {
						webView.requestFocus();
						scrollTo(webView, scrollHPosition, scrollVPosition);
					}
				}
			});
		});

		webView.focusedProperty().addListener((observable, oldValue, newValue) -> {
			pseudoClassStateChanged(CONTAINS_FOCUS_PSEUDOCLASS_STATE, newValue);
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					if (PlatformImpl.isSupported(ConditionalFeature.VIRTUAL_KEYBOARD)) {
						Scene scene = getSkinnable().getScene();
						if (newValue) {
							FXVK.attach(webView);
						} else if (scene == null || scene.getWindow() == null || !scene.getWindow().isFocused()
								|| !(scene.getFocusOwner() instanceof TextInputControl)) {
							FXVK.detach();
						}
					}
				}
			});
		});
		webView.getEngine().getLoadWorker().workDoneProperty().addListener((observable, oldValue, newValue) -> {
			Platform.runLater(() -> {
				webView.requestLayout();
				scrollTo(webView, scrollHPosition, scrollVPosition);
				if (previousCaretPosition != null && caretBounds != null) {
					// we must had the caret size/2. Easy solution : + 1.
					simulateClick(previousCaretPosition[0] + 1, previousCaretPosition[1] + 1, caretBounds.getX() + 1,
							caretBounds.getY() + 1);
					try {
						Thread.sleep(10);
						if (autoCompletionMessage != null) {
							for (int i = 0; i < autoCompletionMessage.length(); i++) {
								final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1);
								executor.schedule(new Runnable() {
									@Override
									public void run() {
										Platform.runLater(() -> {
											webView.fireEvent(new KeyEvent(webView, webView, KeyEvent.KEY_PRESSED, "",
													"", KeyCode.RIGHT, false, false, false, false));
											webView.fireEvent(new KeyEvent(webView, webView, KeyEvent.KEY_RELEASED, "",
													"", KeyCode.RIGHT, false, false, false, false));
										});
									}
									//Arbitrary time, we hope that the thread will be ready to receive new events !
								}, 50, TimeUnit.MILLISECONDS);
							}
							autoCompletionMessage = null;
						}

					} catch (InterruptedException e1) {
						e1.printStackTrace();
					}
				}
			});

			double totalWork = webView.getEngine().getLoadWorker().getTotalWork();
			if (newValue.doubleValue() == totalWork) {
				cachedHTMLText = null;
			}
		});

		webView.addEventHandler(MouseEvent.MOUSE_MOVED, e -> {
			this.mouseScreenX = e.getScreenX();
			this.mouseScreenY = e.getScreenY();
		});

		webView.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> Platform.runLater(() -> {
			updateCaretAndScrollPosition();
			if (!e.isSynthesized()) {
				getBehavior().mousePressed(e);
			}
		}));

		webView.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> Platform.runLater(() -> {
			updateCaretAndScrollPosition();
			if (!e.isSynthesized()) {
				if (!getSelectedText().equals("")) {
					execute();
				}
				getBehavior().mouseReleased(e);
			}
		}));

		webView.addEventHandler(KeyEvent.KEY_TYPED, e -> Platform.runLater(() -> {
			updateCaretAndScrollPosition();
			execute();
		}));

		webView.setOnKeyReleased(e -> Platform.runLater(() -> {
			getBehavior().onKeyReleased(e);
		}));

		setHTMLText(cachedHTMLText);
		webView.setFocusTraversable(false);
		gridPane.getChildren().addListener(itemsListener);
		
		execute();
	}

	/**
	 * Update caret and scroll position only if there is no selected text (else
	 * the value is equals to 0).
	 */
	private void updateCaretAndScrollPosition() {
		if (getSelectedText().equals("") && !getBehavior().isShowing()) {
			String renderTree = webPage.getRenderTree(webPage.getMainFrame());
			int caretIndex = renderTree.lastIndexOf("caret: ");
			if (caretIndex != -1) {
				String caretInfo = renderTree.substring(caretIndex);
				String info[] = caretInfo.split(" ");
				if(info.length > 9) {
					caretColPos = Integer.valueOf(info[2]);
					caretLinePos = Integer.valueOf(info[9]);
				}
				this.previousCaretPosition = webPage.getClientTextLocation(0);
				this.caretBounds = webPage.getPageClient()
						.windowToScreen(new WCPoint(previousCaretPosition[0] + previousCaretPosition[2],
								previousCaretPosition[1] + previousCaretPosition[3]));
				this.boundsInScene = webView.localToScene(webView.getBoundsInLocal());
				this.scrollHPosition = getHScrollValue(webView);
				this.scrollVPosition = getVScrollValue(webView);
			}
		}
	}

	/**
	 * @return cached HTML or request to webPage the HTML text.
	 */
	public final String getHTMLText() {
		return cachedHTMLText != null ? cachedHTMLText : webPage.getHtml(webPage.getMainFrame());
	}

	/**
	 * Update current cached text and load the webPage with this html text.
	 * 
	 * @param htmlText
	 *            the html text.
	 */
	public final void setHTMLText(String htmlText) {
		cachedHTMLText = htmlText;
		webPage.load(webPage.getMainFrame(), htmlText, "text/html");
	}

	@Override
	protected void layoutChildren(final double x, final double y, final double w, final double h) {
		super.layoutChildren(x, y, w, h);
	}

	/**
	 * Print job.
	 * 
	 * @param job
	 *            the job
	 */
	public void print(PrinterJob job) {
		webView.getEngine().print(job);
	}

	/**
	 * Organize highlights with the offset/css type/priority. Synchronized.
	 * 
	 * @return highlights sections
	 */
	public List<HighLightSection> computeHighlights() {
		synchronized (monitorHighLights) {
			return highLight.computeHighlights();
		}
	}

	/**
	 * Remove HighLights. Synchronized.
	 */
	public void removeHighlights() {
		synchronized (monitorHighLights) {
			highLight.clear();
		}
	}

	/**
	 * Add a HighLight. Synchronized.
	 * 
	 * @param offset
	 *            the offset
	 * @param length
	 *            the length of text to highlights
	 * @param type
	 *            the type
	 */
	public void highlight(int offset, int length, String type) {
		synchronized (monitorHighLights) {
			highLight.add(offset, length, type);
		}
	}

	/**
	 * @return the webpage current selected text.
	 */
	public String getSelectedText() {
		return webPage.getClientSelectedText();
	}

	/**
	 * @return the caret position in user coordinate.
	 */
	public int getCaretPosition() {
		updateCaretAndScrollPosition();
		String txt = getText();
		String[] lines = txt.split(EOL, -1);
		int length = 0;
		for (int i = 0; i < caretLinePos; i++) {
			length = length + lines[i].length() + EOL.length();
		}
		return caretColPos + length;
	}

	/**
	 * @return caret bounds.
	 */
	public WCPoint getCaretBounds() {
		return caretBounds;
	}

	/**
	 * Add the current text at the end of the text in a html div tag.
	 * 
	 * @param msg
	 *            the message to print
	 */
	public void println(String msg) {
		String stringx = "<div>" + parseToHTML(msg) + "</div>";
		String html = getHTMLText();
		int endIndex = html.indexOf("</body></html>");
		setHTMLText(html.substring(0, endIndex) + stringx + html.substring(endIndex));
	}

	/**
	 * Insert the text at the indicated offset. User offset coordinates.
	 * 
	 * @param offset
	 *            the offset
	 * @param msg
	 *            the message to insert
	 */
	public void insertText(int offset, String msg) {
		int htmlOffset = getCorrespondingOffset(offset);
		String html = getHTMLText();
		setHTMLText(html.substring(0, htmlOffset) + parseToHTML(msg) + html.substring(htmlOffset));
	}
	
	/**
	 * @param msg message
	 * @return String with encoded special characters.
	 */
	private String parseToHTML(String msg) {
		msg = msg.replaceAll("&", "&amp;");
		msg = msg.replaceAll(" ", "&nbsp;");
		msg = msg.replaceAll("<", "&lt;");
		msg = msg.replaceAll(">", "&gt;");
		return msg;
	}

	/**
	 * Replace the indicated text by start and end offsets with the message in
	 * arguments. User offset coordinates.
	 * 
	 * @param start
	 *            start offset
	 * @param end
	 *            end offset
	 * @param msg
	 *            the text to insert
	 */
	public void replaceText(int start, int end, String msg) {
		int startHtmlOffset = getCorrespondingOffset(start);
		int endHtmlOffset = getCorrespondingOffset(end);
		String html = getHTMLText();
		setHTMLText(html.substring(0, startHtmlOffset) + parseToHTML(msg) + html.substring(endHtmlOffset));
		execute();
	}

	/**
	 * Convert the user offset with html system offset.
	 * 
	 * @param offset
	 *            the offset in user coordinates.
	 * @return offset in html coordinates.
	 */
	public int getCorrespondingOffset(int offset) {
		String html = getHTMLText();
		String toFind = "contenteditable=\"true\">";
		int startIndex = html.indexOf(toFind) + toFind.length();
		int index = startIndex;
		//special case where the text is clear, then we set a <div> tag.
		if(!html.contains("<div>") || !html.substring(index, index + "<div>".length()).equals("<div>")) {
			String stringx = "<div>" + parseToHTML(getText()) + "</div>";
			int endIndex = html.indexOf("</body></html>"); 
			html = html.substring(0, index) + stringx + html.substring(endIndex);
			setHTMLText(html); 
			System.out.println(html);
		}
		try {
			int calculateOffset = -1;
			while (calculateOffset < offset) {
				if (html.charAt(index) == '<') {
					int endIndex = html.indexOf('>', index);
					String tag = html.substring(index, endIndex + 1);
					if (tag.equals("</div>")) { // || tag.equals("<br>") ) {
						// new line
						calculateOffset = calculateOffset + EOL.length();
						if (calculateOffset == offset) {
							// found
							break;
						}
					} else if (tag.equals("<br>")) {
						// special case <br></div> ! we must return the index before
						// the <br> !
						int newIndex = html.indexOf('>', index) + 1;
						if (html.charAt(newIndex) == '<') {
							int newEndIndex = html.indexOf('>', newIndex);
							String newTag = html.substring(newIndex, newEndIndex + 1);
							if (newTag.equals("</div>")) {
								if (calculateOffset + 1 == offset) {
									return index;
								}
							}
						}
					} 
					index = endIndex;
				} else if (html.charAt(index) == '&') {
					int endIndex = html.indexOf(';', index);
					String tag = html.substring(index, endIndex + 1);
					if (tag.equals("&gt;") || tag.equals("&lt;") || tag.equals("&nbsp;") || tag.equals("&amp;")) {
						calculateOffset++;
						if (calculateOffset == offset) {
							// found
							break;
						}
						index = endIndex;
					}
				} else {
					calculateOffset++;
				}
				if (calculateOffset == offset) {
					// found
					break;
				}
				index++;
			}
			return index;
		} catch(StringIndexOutOfBoundsException e) {
			return startIndex + "<div>".length();
		}
	}

	private void simulateClick(double x, double y, double screenX, double screenY) {
		webView.fireEvent(new MouseEvent(MouseEvent.MOUSE_PRESSED, boundsInScene.getMinX() + x,
				boundsInScene.getMinY() + y, screenX, screenY, MouseButton.PRIMARY, 1, false, false, false, false, true,
				false, false, true, false, false, null));
		webView.fireEvent(new MouseEvent(MouseEvent.MOUSE_RELEASED, boundsInScene.getMinX() + x,
				boundsInScene.getMinY() + y, screenX, screenY, MouseButton.PRIMARY, 1, false, false, false, false,
				false, false, false, true, false, false, null));
	}

	/**
	 * @return the user text by replacing the html tags.
	 */
	public String getText() {
		String html = getHTMLText();
		html = html.replaceAll("<div>", "");
		html = html.replaceAll("</div>", System.lineSeparator());
		html = html.replaceAll("\\<.*?>", "");
		html = html.replaceAll("&nbsp;", " ");
		html = html.replaceAll("&lt;", "<");
		html = html.replaceAll("&gt;", ">");
		html = html.replaceAll("&amp;", "&");
		return html;
	}

	/**
	 * Update the style.
	 */
	public void updateStyle() {
		String htmlOrigin = getHTMLText();
		htmlOrigin = htmlOrigin.replaceAll("<span.*?>", "");
		htmlOrigin = htmlOrigin.replaceAll("</span>", "");
		setHTMLText(htmlOrigin);

		List<HighLightSection> highLightSections = computeHighlights();

		for (HighLightSection highLight : highLightSections) {
			int start = highLight.getStart();
			int end = highLight.getEnd();
			int htmlStart = getCorrespondingOffset(start);
			int htmlEnd = getCorrespondingOffset(end);

			StringBuilder styles = new StringBuilder();
			for (String style : highLight.getType()) {
				if (styles.length() > 0) {
					styles.append(" ");
				}
				styles.append(style);
			}
			String html = getHTMLText();
			setHTMLText(html.substring(0, htmlStart) + "<span class=\"" + styles.toString() + "\">"
					+ html.substring(htmlStart, htmlEnd) + "</span>" + html.substring(htmlEnd));
		}
	}

	/**
	 * set current timer to 0. Update style action.
	 */
	public void execute() {
		pauseUpdateStyle.playFromStart();
	}

	/**
	 * Redirect reInitTemplates to behavior class.
	 */
	public void reInitTemplates() {
		getBehavior().reInitTemplates();
	}

	/**
	 * Show action popup.
	 * 
	 * @param inputCallBack
	 *            action input call back
	 * @param msg
	 *            the message
	 */
	public void actionPopup(ActionInputCallBack inputCallBack, String msg) {
		getBehavior().showActionPopup(inputCallBack, msg);
	}

	/**
	 * Scroll to the indicated positions.
	 * 
	 * @param view
	 *            the web view
	 * @param x
	 *            horizontal scroll position
	 * @param y
	 *            vertical scroll position
	 */
	public void scrollTo(WebView view, int x, int y) {
		view.getEngine().executeScript("window.scrollTo(" + x + ", " + y + ")");
	}

	/**
	 * @param view
	 *            the web view
	 * @return vertical scroll position.
	 */
	public int getVScrollValue(WebView view) {
		return (Integer) view.getEngine().executeScript("document.body.scrollTop");
	}

	/**
	 * @param view
	 *            the web view
	 * @return horizontal scroll position.
	 */
	public int getHScrollValue(WebView view) {
		return (Integer) view.getEngine().executeScript("document.body.scrollLeft");
	}

	/**
	 * @return mouse screen coordinates.
	 */
	public WCPoint getMouseBounds() {
		return new WCPoint((float) mouseScreenX, (float) mouseScreenY);
	}

	/**
	 * Insert the message at the offset position and move the caret of 'length'
	 * of message character.
	 * 
	 * @param offset
	 *            the offset
	 * @param msg
	 *            the message
	 */
	public void insertTextAndUpdateCaretPosition(int offset, String msg) {
		this.autoCompletionMessage = msg;
		insertText(offset, msg);
	}

}
