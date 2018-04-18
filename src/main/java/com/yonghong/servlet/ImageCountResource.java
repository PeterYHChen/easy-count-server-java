package com.yonghong.servlet;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Base64;

import javax.imageio.ImageIO;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;

import org.bytedeco.javacpp.Loader;
import org.bytedeco.javacpp.opencv_java;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

@Path("/count")
public class ImageCountResource extends Application {
	static {
		Loader.load(opencv_java.class);
	}
	private static final long serialVersionUID = 1L;
	private static final Gson gson = new GsonBuilder().create();

	@GET
	@Produces(MediaType.APPLICATION_JSON)
	public String getHello() throws IOException {
		return "Hello World";
	}

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	public String doPost(String data) throws IOException {
		ImageData imageData = gson.fromJson(data, ImageData.class);
		try {
			String[] imageDataUrl = imageData.image.split(",");
			String imageHeader = imageDataUrl[0];
			String imageBase64 = imageDataUrl[1];
			byte[] imageBytes = Base64.getDecoder().decode(imageBase64);

			ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes);
			BufferedImage image = ImageIO.read(bis);
			bis.close();
			image = detectCircles(image);

			String resultBase64 = encodeImageToString(image, "jpg");
			imageData.image = imageHeader + "," + resultBase64;

		} catch (Exception e) {
			// TODO: handle exception
			return getExceptionStack(e);
		}
		return gson.toJson(imageData);
	}

	private String getExceptionStack(Exception e) {
		StringWriter errors = new StringWriter();
		e.printStackTrace(new PrintWriter(errors));
		return errors.toString();
	}

	public static String encodeImageToString(BufferedImage image, String type) {
		String imageString = null;
		ByteArrayOutputStream bos = new ByteArrayOutputStream();

		try {
			ImageIO.write(image, type, bos);
			byte[] imageBytes = bos.toByteArray();
			imageString = Base64.getEncoder().encodeToString(imageBytes);

			bos.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return imageString;
	}

	private static BufferedImage detectCircles(BufferedImage image) {
		// Used to draw on the image
		Graphics2D imageGraphics = (Graphics2D) image.getGraphics();

		// Compute color similarity first
		boolean[][] isObject = new boolean[image.getWidth()][image.getHeight()];
		for (int x = 0; x < image.getWidth(); x++) {
			for (int y = 0; y < image.getHeight(); y++) {
				if (image.getRGB(x, y) == 0) {
					isObject[x][y] = false;
					continue;
				}
				isObject[x][y] = colorsAreSimilar(image.getRGB(x, y), Color.decode("#CDCAB9").getRGB(), 100);
			}
		}

		/* convert bitmap to mat */
		Mat mat = colorImageToMat(image);
		Mat grayMat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1);

		/* convert to grayscale */
		int colorChannels = (mat.channels() == 3 || mat.channels() == 4) ? Imgproc.COLOR_BGR2GRAY : 1;

		Imgproc.cvtColor(mat, grayMat, colorChannels);
		// Imgproc.equalizeHist(grayMat, grayMat);
		/* reduce the noise so we avoid false circle detection */
		// Imgproc.dilate(grayMat, grayMat, new Mat());
		// Imgproc.GaussianBlur(grayMat, grayMat, new Size(5, 5), 2, 2);
		Imgproc.blur(grayMat, grayMat, new Size(5, 5));

		// accumulator value
		double dp = 1;
		// minimum distance between the center coordinates of detected circles in pixels
		double minDist = 20;

		// min and max radii (set these values as you desire)
		int minRadius = 20, maxRadius = 40;

		// param1 = gradient value used to handle edge detection
		// param2 = Accumulator threshold value for the
		// cv2.CV_HOUGH_GRADIENT method.
		// The smaller the threshold is, the more circles will be
		// detected (including false circles).
		// The larger the threshold is, the more circles will
		// potentially be returned.
		double param1 = 40, param2 = 10;

		/* create a Mat object to store the circles detected */
		Mat circles = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC1);

		/* find the circle in the image */
		Imgproc.HoughCircles(grayMat, circles, Imgproc.CV_HOUGH_GRADIENT, dp, minDist, param1, param2, minRadius,
				maxRadius);

		/* get the number of circles detected */
		int numberOfCircles = circles.cols();
		/* draw the circles found on the image */
		for (int i = 0; i < numberOfCircles; i++) {
			/*
			 * get the circle details, circleCoordinates[0, 1, 2] = (x,y,r) (x,y) are the
			 * coordinates of the circle's center
			 */
			double[] circleCoordinates = circles.get(0, i);

			int x = (int) circleCoordinates[0], y = (int) circleCoordinates[1];
			Point center = new Point(x, y);

			int radius = (int) circleCoordinates[2];

			// Take random points in the circle and evaluate how many points have a similar
			// color to the object
			int area = (int) (Math.PI * radius * radius);
			int numOfRandomPoints = area / 10;
			double randomAngle, randomR;
			int randomX, randomY;
			int numOfObjectPoints = 0;
			for (int n = 0; n < numOfRandomPoints; n++) {
				randomAngle = 2 * Math.PI * Math.random();
				randomR = radius * Math.sqrt(Math.random());
				randomX = (int) (x + randomR * Math.cos(randomAngle));
				randomY = (int) (y + randomR * Math.sin(randomAngle));
				// Ignore out of bound points
				if (randomX < 0 || randomX >= image.getWidth() || randomY < 0 || randomY >= image.getHeight()) {
					n--;
					continue;
				}
				if (isObject[randomX][randomY]) {
					numOfObjectPoints++;
					image.setRGB(randomX, randomY, Color.GREEN.getRGB());
				} else {
					image.setRGB(randomX, randomY, Color.RED.getRGB());
				}
			}

			/* Draw circle's outline when it is more likely to be an object */
			// if ((double) numOfObjectPoints / numOfRandomPoints > 0.6) {
			imageGraphics.setColor(Color.BLACK);
			imageGraphics.drawOval(x - radius, y - radius, radius * 2, radius * 2);
			// }
		}

		return image;
	}

	// Put image data into a matrix
	private static Mat colorImageToMat(BufferedImage image) {
		Mat mat = new Mat(image.getHeight(), image.getWidth(), CvType.CV_8UC3);
		mat.put(0, 0, ((DataBufferByte) image.getRaster().getDataBuffer()).getData());
		return mat;
	}

	private static BufferedImage matToBufferedImage(Mat mat) {
		byte[] data = new byte[mat.rows() * mat.cols() * (int) (mat.elemSize())];
		mat.get(0, 0, data);
		int imageType;
		if (mat.channels() == 1) {
			imageType = BufferedImage.TYPE_BYTE_GRAY;
		} else {
			imageType = BufferedImage.TYPE_3BYTE_BGR;
		}

		BufferedImage image = new BufferedImage(mat.cols(), mat.rows(), imageType);
		image.getRaster().setDataElements(0, 0, mat.cols(), mat.rows(), data);
		return image;
	}

	// use CIE76 ΔE*ab to compute color similarity
	public static boolean colorsAreSimilar(int rgb1, int rgb2, int maxDelta) {
		return colorsAreSimilar(new Color(rgb1), new Color(rgb2), maxDelta);
	}

	// use CIE76 ΔE*ab to compute color similarity
	// a and b are RGB values
	public static boolean colorsAreSimilar(Color a, Color b, int maxDelta) {

		int redDiff = a.getRed() - b.getRed();
		int blueDiff = a.getBlue() - b.getBlue();
		int greenDiff = a.getGreen() - b.getGreen();

		double deltaE = Math.sqrt(2 * redDiff * redDiff + 4 * blueDiff * blueDiff + 3 * greenDiff * greenDiff);
		// System.out.println("deltaE: " + deltaE);
		return deltaE < maxDelta;
	}
}
