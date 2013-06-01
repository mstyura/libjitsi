/*
 * Jitsi, the OpenSource Java VoIP and Instant Messaging client.
 *
 * Distributable under LGPL license.
 * See terms of license at gnu.org.
 */
package org.jitsi.impl.neomedia.codec.video.h264;

import java.awt.*;

import javax.media.*;
import javax.media.format.*;

import net.sf.fmj.media.*;

import org.jitsi.impl.neomedia.*;
import org.jitsi.impl.neomedia.codec.*;
import org.jitsi.impl.neomedia.codec.video.*;
import org.jitsi.service.neomedia.codec.*;
import org.jitsi.service.neomedia.control.*;

/**
 * Decodes H.264 NAL units and returns the resulting frames as FFmpeg
 * <tt>AVFrame</tt>s (i.e. in YUV format).
 *
 * @author Damian Minkov
 * @author Lyubomir Marinov
 * @author Sebastien Vincent
 */
public class JNIDecoder
    extends AbstractCodec
{
    /**
     * The default output <tt>VideoFormat</tt>.
     */
    private static final VideoFormat[] DEFAULT_OUTPUT_FORMATS
        = new VideoFormat[] { new AVFrameFormat(FFmpeg.PIX_FMT_YUV420P) };

    /**
     * The output <tt>VideoFormat</tt> for hardware decoder.
     */
    private static final VideoFormat[] HARDWARE_OUTPUT_FORMATS
        = new VideoFormat[]
                {
                    new VideoFormat(Constants.FFMPEG_H264,
                        null, -1, AVFrame.class, Format.NOT_SPECIFIED)
                };

    /**
     * If decoder supports hardware decoding.
     */
    public static final boolean HARDWARE_DECODING =
        FFmpeg.hw_decoder_is_supported(FFmpeg.CODEC_ID_H264);

    /**
     * Plugin name.
     */
    private static final String PLUGIN_NAME = "H.264 Decoder";

    /**
     *  The codec context native pointer we will use.
     */
    private long avctx;

    /**
     * The <tt>AVFrame</tt> in which the video frame decoded from the encoded
     * media data is stored.
     */
    private AVFrame avframe;

    /**
     * If decoder has got a picture.
     */
    private final boolean[] got_picture = new boolean[1];

    private boolean gotPictureAtLeastOnce;

    /**
     * The last known height of {@link #avctx} i.e. the video output by this
     * <tt>JNIDecoder</tt>. Used to detect changes in the output size.
     */
    private int height;

    /**
     * The <tt>KeyFrameControl</tt> used by this <tt>JNIDecoder</tt> to
     * control its key frame-related logic.
     */
    private KeyFrameControl keyFrameControl;

    /**
     * Array of output <tt>VideoFormat</tt>s.
     */
    private final VideoFormat[] outputFormats;

    /**
     * The last known width of {@link #avctx} i.e. the video output by this
     * <tt>JNIDecoder</tt>. Used to detect changes in the output size.
     */
    private int width;

    /**
     * If the decoder will use hardware decoding.
     */
    private boolean useHardwareDecoding = false;

    /**
     * Initializes a new <tt>JNIDecoder</tt> instance which is to decode H.264
     * NAL units into frames in YUV format.
     */
    public JNIDecoder()
    {
        MediaServiceImpl mediaImpl = NeomediaServiceUtils.getMediaServiceImpl();

        /* if FFmpeg supports hardware decoding for H.264 and if configuration
         * is set to use hardware decoding!
         */
        useHardwareDecoding = HARDWARE_DECODING &&
            mediaImpl != null && mediaImpl.isHardwareDecodingEnabled();

        inputFormats = new VideoFormat[] { new VideoFormat(Constants.H264) };
        
        if(useHardwareDecoding)
        {
            outputFormats = HARDWARE_OUTPUT_FORMATS;
        }
        else
        {
            outputFormats = DEFAULT_OUTPUT_FORMATS;
        }
    }

    /**
     * Check <tt>Format</tt>.
     *
     * @param format <tt>Format</tt> to check
     * @return true if <tt>Format</tt> is H264_RTP
     */
    public boolean checkFormat(Format format)
    {
        return format.getEncoding().equals(Constants.H264_RTP);
    }

    /**
     * Close <tt>Codec</tt>.
     */
    @Override
    public synchronized void close()
    {
        if (opened)
        {
            opened = false;
            super.close();

            FFmpeg.avcodec_close(avctx);
            FFmpeg.av_free(avctx);
            avctx = 0;

            if (avframe != null)
            {
                avframe.free();
                avframe = null;
            }

            gotPictureAtLeastOnce = false;
        }
    }

    /**
     * Ensure frame rate.
     *
     * @param frameRate frame rate
     * @return frame rate
     */
    private float ensureFrameRate(float frameRate)
    {
        return frameRate;
    }

    /**
     * Get matching outputs for a specified input <tt>Format</tt>.
     *
     * @param inputFormat input <tt>Format</tt>
     * @return array of matching outputs or null if there are no matching
     * outputs.
     */
    protected Format[] getMatchingOutputFormats(Format inputFormat)
    {
        VideoFormat inputVideoFormat = (VideoFormat) inputFormat;

        if(useHardwareDecoding)
        {
            return
                new Format[]
                {
                    new VideoFormat(Constants.FFMPEG_H264,
                            inputVideoFormat.getSize(),
                            -1,
                            AVFrame.class,
                            ensureFrameRate(inputVideoFormat.getFrameRate())),
                };
        }
        else
        {
            return
                new Format[]
                {
                    new AVFrameFormat(
                        inputVideoFormat.getSize(),
                        ensureFrameRate(inputVideoFormat.getFrameRate()),
                        FFmpeg.PIX_FMT_YUV420P)
                };
        }
    }

    /**
     * Get plugin name.
     *
     * @return "H.264 Decoder"
     */
    @Override
    public String getName()
    {
        return PLUGIN_NAME;
    }

    /**
     * Get all supported output <tt>Format</tt>s.
     *
     * @param inputFormat input <tt>Format</tt> to determine corresponding
     * output <tt>Format/tt>s
     * @return an array of supported output <tt>Format</tt>s
     */
    @Override
    public Format[] getSupportedOutputFormats(Format inputFormat)
    {
        Format[] supportedOutputFormats;

        if (inputFormat == null)
            supportedOutputFormats = outputFormats;
        else
        {
            // mismatch input format
            if (!(inputFormat instanceof VideoFormat)
                    || (AbstractCodec2.matches(inputFormat, inputFormats)
                            == null))
                supportedOutputFormats = new Format[0];
            else
            {
                // match input format
                supportedOutputFormats = getMatchingOutputFormats(inputFormat);
            }
        }
        return supportedOutputFormats;
    }

    /**
     * Inits the codec instances.
     *
     * @throws ResourceUnavailableException if codec initialization failed
     */
    @Override
    public synchronized void open()
        throws ResourceUnavailableException
    {
        if (opened)
            return;

        if (avframe != null)
        {
            avframe.free();
            avframe = null;
        }
        avframe = new AVFrame();

        long avcodec = FFmpeg.avcodec_find_decoder(FFmpeg.CODEC_ID_H264);

        avctx = FFmpeg.avcodec_alloc_context3(avcodec);
        FFmpeg.avcodeccontext_set_workaround_bugs(avctx,
                FFmpeg.FF_BUG_AUTODETECT);

        /* hardware decoding does not deals very well
         * with the CODEC_FLAG2_CHUNKS flag
         */
        if(!useHardwareDecoding)
        {
            /* allow to pass incomplete frame to decoder */
            FFmpeg.avcodeccontext_add_flags2(avctx,
                FFmpeg.CODEC_FLAG2_CHUNKS);
            
            /* explicitely inform FFmpeg that we don't want to use hardware
             * decoding either because we don't support it or configuration
             * said so!
             */
            FFmpeg.avcodeccontext_set_opaque(avctx, 0);
        }
        else
        {
            /* explicitely inform FFmpeg that we will use hardware decoding */
            FFmpeg.avcodeccontext_set_opaque(avctx, 0xBEEFACCE);
        }

        if (FFmpeg.avcodec_open2(avctx, avcodec) < 0)
            throw new RuntimeException("Could not open codec CODEC_ID_H264");

        gotPictureAtLeastOnce = false;

        opened = true;
        super.open();
    }

    /**
     * Decodes H.264 media data read from a specific input <tt>Buffer</tt> into
     * a specific output <tt>Buffer</tt>.
     *
     * @param in input <tt>Buffer</tt>
     * @param out output <tt>Buffer</tt>
     * @return <tt>BUFFER_PROCESSED_OK</tt> if <tt>in</tt> has been successfully
     * processed
     */
    @Override
    public synchronized int process(Buffer in, Buffer out)
    {
        if (!checkInputBuffer(in))
            return BUFFER_PROCESSED_FAILED;
        if (isEOM(in) || !opened)
        {
            propagateEOM(out);
            return BUFFER_PROCESSED_OK;
        }
        if (in.isDiscard())
        {
            out.setDiscard(true);
            return BUFFER_PROCESSED_OK;
        }

        // Ask FFmpeg to decode.
        got_picture[0] = false;
        // TODO Take into account the offset of inputBuffer.
        FFmpeg.avcodec_decode_video(
                avctx,
                avframe.getPtr(),
                got_picture,
                (byte[]) in.getData(), in.getLength());

        if (!got_picture[0])
        {
            if ((in.getFlags() & Buffer.FLAG_RTP_MARKER) != 0)
            {
                if (keyFrameControl != null)
                    keyFrameControl.requestKeyFrame(!gotPictureAtLeastOnce);
            }

            out.setDiscard(true);
            return BUFFER_PROCESSED_OK;
        }
        gotPictureAtLeastOnce = true;

        // format
        int width = FFmpeg.avcodeccontext_get_width(avctx);
        int height = FFmpeg.avcodeccontext_get_height(avctx);

        if ((width > 0)
                && (height > 0)
                && ((this.width != width) || (this.height != height)))
        {
            this.width = width;
            this.height = height;

            // Output in same size and frame rate as input.
            Dimension outSize = new Dimension(this.width, this.height);
            VideoFormat inFormat = (VideoFormat) in.getFormat();
            float outFrameRate = ensureFrameRate(inFormat.getFrameRate());

            if(useHardwareDecoding)
            {
                outputFormat
                    = new VideoFormat(Constants.FFMPEG_H264, outSize,
                        -1, AVFrame.class, outFrameRate);
            }
            else
            {
                outputFormat
                    = new AVFrameFormat(
                            outSize,
                            outFrameRate,
                            FFmpeg.PIX_FMT_YUV420P);
            }
        }
        out.setFormat(outputFormat);

        // data
        if (out.getData() != avframe)
            out.setData(avframe);

        // timeStamp
        long pts = FFmpeg.AV_NOPTS_VALUE; // TODO avframe_get_pts(avframe);

        if (pts == FFmpeg.AV_NOPTS_VALUE)
            out.setTimeStamp(Buffer.TIME_UNKNOWN);
        else
        {
            out.setTimeStamp(pts);

            int outFlags = out.getFlags();

            outFlags |= Buffer.FLAG_RELATIVE_TIME;
            outFlags &= ~(Buffer.FLAG_RTP_TIME | Buffer.FLAG_SYSTEM_TIME);
            out.setFlags(outFlags);
        }

        return BUFFER_PROCESSED_OK;
    }

    /**
     * Sets the <tt>Format</tt> of the media data to be input for processing in
     * this <tt>Codec</tt>.
     *
     * @param format the <tt>Format</tt> of the media data to be input for
     * processing in this <tt>Codec</tt>
     * @return the <tt>Format</tt> of the media data to be input for processing
     * in this <tt>Codec</tt> if <tt>format</tt> is compatible with this
     * <tt>Codec</tt>; otherwise, <tt>null</tt>
     */
    @Override
    public Format setInputFormat(Format format)
    {
        Format setFormat = super.setInputFormat(format);

        if (setFormat != null)
            reset();
        return setFormat;
    }

    /**
     * Sets the <tt>KeyFrameControl</tt> to be used by this
     * <tt>DePacketizer</tt> as a means of control over its key frame-related
     * logic.
     *
     * @param keyFrameControl the <tt>KeyFrameControl</tt> to be used by this
     * <tt>DePacketizer</tt> as a means of control over its key frame-related
     * logic
     */
    public void setKeyFrameControl(KeyFrameControl keyFrameControl)
    {
        this.keyFrameControl = keyFrameControl;
    }
}
