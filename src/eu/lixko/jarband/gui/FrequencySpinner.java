package eu.lixko.jarband.gui;

import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

import javax.swing.JComponent;
import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.text.DefaultFormatter;

public class FrequencySpinner extends JSpinner implements MouseWheelListener, MouseListener {

	private static final long serialVersionUID = 3109891297969525942L;
	
	protected final int oneCharWidth;
	
	protected final JFormattedTextField textField;
	
	public FrequencySpinner(SpinnerNumberModel spinnerModel) {
		super(spinnerModel);
		
		this.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 24));
		
        JComponent comp = this.getEditor();
        this.textField = (JFormattedTextField) comp.getComponent(0);
        DefaultFormatter formatter = (DefaultFormatter) textField.getFormatter();
        formatter.setCommitsOnValidEdit(true);
        
        this.oneCharWidth = textField.getFontMetrics(textField.getFont()).charWidth('0');
        
        this.textField.addMouseWheelListener(this);
        this.textField.addMouseListener(this);
        
        this.setValue(spinnerModel.getValue());
	}

	public static FrequencySpinner makeSpinner(double defaultFreq, double minFreq, double maxFreq) {
    	SpinnerNumberModel spinnerModel = new SpinnerNumberModel(
			defaultFreq,
			minFreq, //minimum value  
			maxFreq, //maximum value  
            1d
        );
    	return new FrequencySpinner(spinnerModel);
	}
	
    public SpinnerNumberModel getModel() {
        return (SpinnerNumberModel) super.getModel();
    }
    
    public double getFrequency() {
    	return (Double) this.getValue();
    }
    
    public void setFrequency(double freq) {
    	freq = Math.clamp(freq, (Double) this.getModel().getMinimum(), (Double) this.getModel().getMaximum());
    	this.setValue(freq);
    }
    
    /**
     * Get the order of magnitude on which the mouse is hovered.
     * The result is 1-indexed (1 being the right-most digit).  
     * @param event
     * @return
     */
    protected int getCharN(MouseEvent event) {
        Point coords = event.getPoint();
        int fromRight = this.textField.getWidth() - coords.x;
        int charN = fromRight / oneCharWidth + 1; // 1-indexed
        return charN;
    }

	@Override
    public void mouseWheelMoved(MouseWheelEvent event) {
        if (event.getScrollType() != MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            return;
        }
        
        int charN = this.getCharN(event);
        int exp = charN - charN/4; // deal with commas
        
        double diff = Math.pow(10d, exp - 1) * event.getPreciseWheelRotation() * -1d;
        if (charN % 4 == 0) { // is hovering over a comma
        	diff *= 5d;
        }
        
        double targetValue = this.getFrequency() + diff;
        this.setFrequency(targetValue);
    }

	@Override
	public void mouseClicked(MouseEvent event) {
		if (event.getButton() != MouseEvent.BUTTON3) {
			return;
		}
		
		// on the right click of a digit, set the following digits to 0

		int charN = this.getCharN(event);
		int exp = charN - charN/4; // deal with commas
        if (charN % 4 == 0) { // is hovering over a comma
        	exp++;
        }
        
		double coef = Math.pow(10d, exp - 1);
		double val = this.getFrequency();
		val = Math.floor(val / coef) * coef;
		this.setFrequency(val);
	}

	@Override
	public void mousePressed(MouseEvent e) {
	}

	@Override
	public void mouseReleased(MouseEvent e) {
	}

	@Override
	public void mouseEntered(MouseEvent e) {
	}

	@Override
	public void mouseExited(MouseEvent e) {
	}

}
