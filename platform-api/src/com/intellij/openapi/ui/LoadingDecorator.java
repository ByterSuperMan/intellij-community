package com.intellij.openapi.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.IconLoader;
import com.intellij.ui.components.panels.NonOpaquePanel;
import com.intellij.util.Alarm;
import com.intellij.util.ui.Animator;
import com.intellij.util.ui.AsyncProcessIcon;
import com.intellij.util.ui.UIUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;

public class LoadingDecorator {

  JLayeredPane myPane = new MyLayeredPane();

  LoadingLayer myLoadingLayer;
  Animator myFadeOutAnimator;

  int myDelay;
  Alarm myStartAlarm;
  boolean myStartRequest;


  public LoadingDecorator(JComponent content, Disposable parent, int startDelayMs) {
    myLoadingLayer = new LoadingLayer();
    myDelay = startDelayMs;
    myStartAlarm = new Alarm(Alarm.ThreadToUse.SHARED_THREAD, parent);

    setLoadingText("Loading...");


    myFadeOutAnimator = new Animator("Loading", 10, 500, false, 0, 1) {
      public void paintNow(final float frame, final float totalFrames, final float cycle) {
        myLoadingLayer.setAlpha(1f - frame / totalFrames);
      }

      @Override
      protected void paintCycleEnd() {
        myLoadingLayer.setVisible(false);
        myLoadingLayer.setAlpha(-1);
      }
    };
    Disposer.register(parent, myFadeOutAnimator);


    myPane.add(content, 0, JLayeredPane.DEFAULT_LAYER);
    myPane.add(myLoadingLayer, 1, JLayeredPane.DRAG_LAYER);

    Disposer.register(parent, myLoadingLayer.myProgress);
  }

  public JComponent getComponent() {
    return myPane;
  }

  public void startLoading(final boolean takeSnapshot) {
    if (isLoading() || myStartRequest) return;

    myStartRequest = true;
    if (myDelay > 0) {
      myStartAlarm.addRequest(new Runnable() {
        public void run() {
          UIUtil.invokeLaterIfNeeded(new Runnable() {
            public void run() {
              if (!myStartRequest) return;
              _startLoading(takeSnapshot);
            }
          });
        }
      }, myDelay);
    } else {
      _startLoading(takeSnapshot);
    }
  }

  private void _startLoading(final boolean takeSnapshot) {
    myLoadingLayer.setVisible(true, takeSnapshot);
  }

  public void stopLoading() {
    myStartRequest = false;
    myStartAlarm.cancelAllRequests();

    if (!isLoading()) return;

    myLoadingLayer.setVisible(false, false);
  }


  public String getLoadingText() {
    return myLoadingLayer.myText.getText();
  }

  public void setLoadingText(final String loadingText) {
    myLoadingLayer.myText.setText(loadingText);
  }

  public boolean isLoading() {
    return myLoadingLayer.isVisible();
  }

  private class LoadingLayer extends JPanel {

    private JLabel myText;

    private BufferedImage mySnapshot;
    private Color mySnapshotBg;

    private AsyncProcessIcon myProgress = new AsyncProcessIcon.Big("Loading");

    private boolean myVisible;

    private float myCurrentAlpha;
    private NonOpaquePanel myTextComponent;

    private LoadingLayer() {
      setOpaque(false);
      setVisible(false);
      setLayout(new GridBagLayout());

      myText = new JLabel("", JLabel.CENTER);
      final Font font = myText.getFont();
      myText.setFont(font.deriveFont(font.getStyle(), font.getSize() + 8));
      myText.setForeground(Color.black);
      myProgress.setOpaque(false);


      final int gap = new JLabel().getIconTextGap();
      myTextComponent = new NonOpaquePanel(new FlowLayout(FlowLayout.CENTER, gap * 3, 0));
      myTextComponent.add(myProgress);
      myTextComponent.add(myText);

      add(myTextComponent);

      myProgress.suspend();
    }

    public void setVisible(final boolean visible, boolean takeSnapshot) {
      if (myVisible == visible) return;

      if (myVisible && !visible && myCurrentAlpha != -1) return;

      myVisible = visible;
      if (myVisible) {
        setVisible(myVisible);
        myCurrentAlpha = -1;
      }

      if (myVisible) {
        if (takeSnapshot && getWidth() > 0 && getHeight() > 0) {
          mySnapshot = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
          final Graphics2D g = mySnapshot.createGraphics();
          myPane.paint(g);
          final Component opaque = UIUtil.findNearestOpaque(this);
          mySnapshotBg = opaque != null ? opaque.getBackground() : UIUtil.getPanelBackgound();
          g.dispose();
        }
        myProgress.resume();
      } else {
        disposeSnapshot();
        myProgress.suspend();

        myFadeOutAnimator.reset();
        myFadeOutAnimator.resume();
      }
    }

    private void disposeSnapshot() {
      if (mySnapshot != null) {
        mySnapshot.flush();
        mySnapshot = null;
      }
    }

    @Override
    protected void paintComponent(final Graphics g) {
      if (mySnapshot != null) {
        if (mySnapshot.getWidth() == getWidth() && mySnapshot.getHeight() == getHeight()) {
          g.drawImage(mySnapshot, 0, 0, getWidth(), getHeight(), null);
          g.setColor(new Color(200, 200, 200, 240));
          g.fillRect(0, 0, getWidth(), getHeight());
          return;
        } else {
          disposeSnapshot();
        }
      }

      if (mySnapshotBg != null) {
        g.setColor(mySnapshotBg);
        g.fillRect(0, 0, getWidth(), getHeight());
      }
    }

    public void setAlpha(final float alpha) {
      myCurrentAlpha = alpha;
      paintImmediately(myTextComponent.getBounds());
    }

    @Override
    protected void paintChildren(final Graphics g) {
      if (myCurrentAlpha != -1) {
        ((Graphics2D)g).setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, myCurrentAlpha));
      }
      super.paintChildren(g);
    }
  }

  public static void main(String[] args) {
    IconLoader.activate();

    final JFrame frame = new JFrame();
    frame.getContentPane().setLayout(new BorderLayout());

    final JPanel content = new JPanel(new BorderLayout());

    final LoadingDecorator loadingTree = new LoadingDecorator(new JComboBox(), new Disposable() {
      public void dispose() {
      }
    }, -1);

    content.add(loadingTree.getComponent(), BorderLayout.CENTER);

    final JCheckBox loadingCheckBox = new JCheckBox("Loading");
    loadingCheckBox.addActionListener(new ActionListener() {
      public void actionPerformed(final ActionEvent e) {
        if (loadingTree.isLoading()) {
          loadingTree.stopLoading();
        } else {
          loadingTree.startLoading(false);
        }
      }
    });

    content.add(loadingCheckBox, BorderLayout.SOUTH);


    frame.getContentPane().add(content, BorderLayout.CENTER);

    frame.setBounds(300, 300, 300, 300);
    frame.show();
  }


  private static class MyLayeredPane extends JLayeredPane {
    @Override
    public void doLayout() {
      super.doLayout();
      for (int i = 0; i < getComponentCount(); i++) {
        final Component each = getComponent(i);
        each.setBounds(0, 0, getWidth(), getHeight());
      }
    }
  }
}